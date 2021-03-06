/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package modules

import java.time.ZonedDateTime

import com.github.mauricio.async.db
import com.github.mauricio.async.db.SSLConfiguration
import com.github.mauricio.async.db.pool.{PartitionedConnectionPool, PoolConfiguration}
import com.github.mauricio.async.db.postgresql.pool.PostgreSQLConnectionFactory
import com.github.mauricio.async.db.postgresql.util.URLParser
import io.getquill.{PostgresAsyncContext, SnakeCase}
import javax.inject.{Inject, Singleton}
import models.{Comment, Request, RequestWithTasks, State, Task}
import play.api.inject.{ApplicationLifecycle, Binding, Module}
import play.api.libs.json.{JsObject, Json}
import play.api.{Configuration, Environment}

import scala.concurrent.{ExecutionContext, Future}

class DAOModule extends Module {
  def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] = {
    Seq(
      bind[DAO].to[DAOWithCtx]
    )
  }
}

trait DAO {
  def createRequest(program: String, name: String, creatorEmail: String): Future[Request]
  def createRequest(name: String, creatorEmail: String): Future[Request] = createRequest("default", name, creatorEmail)
  def programRequests(program: String): Future[Seq[RequestWithTasks]]
  def requestsSimilarToName(program: String, name: String): Future[Seq[RequestWithTasks]]
  def programRequests(): Future[Seq[RequestWithTasks]] = programRequests("default")
  def userRequests(email: String): Future[Seq[RequestWithTasks]]
  def request(requestSlug: String): Future[Request]
  def requestWithTasks(requestSlug: String): Future[RequestWithTasks]
  def updateRequest(requestSlug: String, state: State.State, message: Option[String]): Future[Request]
  def createTask(requestSlug: String, prototype: Task.Prototype, completableBy: Seq[String], maybeCompletedBy: Option[String] = None, maybeData: Option[JsObject] = None, state: State.State = State.InProgress): Future[Task]
  def updateTaskState(taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject], maybeCompletionMessage: Option[String]): Future[Task]
  def deleteTask(taskId: Int): Future[Unit]
  def assignTask(taskId: Int, emails: Seq[String]): Future[Task]
  def taskById(taskId: Int): Future[Task]
  def requestTasks(requestSlug: String, maybeState: Option[State.State] = None): Future[Seq[(Task, DAO.NumComments)]]
  def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment]
  def commentsOnTask(taskId: Int): Future[Seq[Comment]]
  def tasksForUser(email: String, state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]]
  def search(maybeProgram: Option[String], maybeState: Option[State.State], data: Option[JsObject]): Future[Seq[RequestWithTasks]]
}

object DAO {
  type NumComments = Long
}

class DAOWithCtx @Inject()(database: DatabaseWithCtx)(implicit ec: ExecutionContext) extends DAO {
  import database.ctx._

  implicit val jsObjectDecoder = MappedEncoding[String, JsObject](Json.parse(_).as[JsObject])
  implicit val jsObjectEncoder = MappedEncoding[JsObject, String](_.toString())

  override def createRequest(program: String, name: String, creatorEmail: String): Future[Request] = {
    // todo: slug choice in transaction with create

    // figure out slug
    val defaultSlug = DB.slug(name)
    val takenSlugsFuture = run {
      quote {
        query[Request].map(_.slug).filter(_.startsWith(lift(defaultSlug)))
      }
    }

    takenSlugsFuture.map(DB.nextSlug(defaultSlug)).flatMap { slug =>
      val state = State.InProgress
      val createDate = ZonedDateTime.now()

      val request = Request(program, slug, name, createDate, creatorEmail, state, None, None)

      run {
        quote {
          query[Request].insert {
            lift(request)
          }
        }
      } map { _ =>
        request
      }
    }
  }

  private def joinedRequestTasksToRequests(requestsTasks: Seq[(Request, Option[Task])]): Seq[RequestWithTasks] = {
    requestsTasks.groupBy(_._1).mapValues(_.map(_._2)).map { case (request, tasks) =>
      RequestWithTasks(request, tasks.flatten)
    }.toSeq
  }

  override def programRequests(program: String): Future[Seq[RequestWithTasks]] = {
    // todo: there has to be a better way to do this query
    run {
      quote {
        query[Request].filter(_.program == lift(program)).leftJoin(query[Task]).on(_.slug == _.requestSlug)
      }
    }.map(joinedRequestTasksToRequests)
  }

  override def userRequests(email: String): Future[Seq[RequestWithTasks]] = {
    // todo: there has to be a better way to do this query
    run {
      quote {
        query[Request].filter(_.creatorEmail == lift(email)).leftJoin(query[Task]).on(_.slug == _.requestSlug)
      }
    }.map(joinedRequestTasksToRequests)
  }

  def requestsSimilarToName(program: String, name: String): Future[Seq[RequestWithTasks]] = {
    run {
      quote {
        query[Request].filter { request =>
          infix"""similarity(${request.name}, ${lift(name)}) >= 0.5""".as[Boolean]
        }.leftJoin(query[Task]).on(_.slug == _.requestSlug)
      }
    }.map(joinedRequestTasksToRequests)
  }

  override def updateRequest(requestSlug: String, state: State.State, message: Option[String]): Future[Request] = {
    val maybeCompletedDate = if (state != State.InProgress) Some(ZonedDateTime.now()) else None

    val updateFuture = run {
      quote {
        query[Request].filter(_.slug == lift(requestSlug)).update(
          _.state -> lift(state),
          _.completedDate -> lift(maybeCompletedDate),
          _.completionMessage -> lift(message)
        )
      }
    }
    updateFuture.flatMap(_ => request(requestSlug))
  }

  override def request(slug: String): Future[Request] = {
    run {
      quote {
        query[Request].filter(_.slug == lift(slug))
      }
    }.flatMap { request =>
      request.headOption.fold(Future.failed[Request](DB.RequestNotFound(slug)))(Future.successful)
    }
  }

  override def requestWithTasks(slug: String): Future[RequestWithTasks] = {
    run {
      quote {
        query[Request].filter(_.slug == lift(slug)).leftJoin(query[Task]).on(_.slug == _.requestSlug)
      }
    }.map(joinedRequestTasksToRequests).flatMap { requestWithTasks =>
      requestWithTasks.headOption.fold(Future.failed[RequestWithTasks](DB.RequestNotFound(slug)))(Future.successful)
    }
  }

  override def createTask(requestSlug: String, prototype: Task.Prototype, completableBy: Seq[String], maybeCompletedBy: Option[String], maybeData: Option[JsObject] = None, state: State.State = State.InProgress): Future[Task] = {
    // todo: move this to a validation module via the DAO
    if (state == State.Completed && maybeCompletedBy.isEmpty) {
      Future.failed(new Exception("maybeCompletedBy was not specified"))
    }
    else {
      val maybeCompletedDate = if (state == State.Completed) Some(ZonedDateTime.now()) else None

      run {
        quote {
          query[Task].insert(
            _.completableBy -> lift(completableBy),
            _.completedBy -> lift(maybeCompletedBy),
            _.requestSlug -> lift(requestSlug),
            _.state -> lift(state),
            _.prototype -> lift(prototype),
            _.data -> lift(maybeData),
            _.completedDate -> lift(maybeCompletedDate)
          ).returning(_.id)
        }
      }.flatMap(taskById)
    }
  }

  def taskById(taskId: Int): Future[Task] = {
    run {
      quote {
        query[Task].filter(_.id == lift(taskId))
      }
    } flatMap { result =>
      result.headOption.fold(Future.failed[Task](new Exception("Task not found")))(Future.successful)
    }
  }

  override def updateTaskState(taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject], maybeCompletionMessages: Option[String]): Future[Task] = {
    // todo: move this to a validation module via the DAO
    if (state != State.InProgress && maybeCompletedBy.isEmpty) {
      Future.failed(new Exception("maybeCompletedBy was not specified"))
    }
    else {
      val maybeCompletedDate = if (state != State.InProgress) Some(ZonedDateTime.now()) else None
      val updateFuture = run {
        quote {
          query[Task].filter(_.id == lift(taskId)).update(
            _.completedBy -> lift(maybeCompletedBy),
            _.state -> lift(state),
            _.completedDate -> lift(maybeCompletedDate),
            _.data -> lift(maybeData),
            _.completionMessage -> lift(maybeCompletionMessages)
          )
        }
      }
      updateFuture.flatMap(_ => taskById(taskId))
    }
  }

  override def deleteTask(taskId: Index): Future[Unit] = {
    val deletes = for {
      _ <- runIO(query[Comment].filter(_.taskId == lift(taskId)).delete)
      _ <- runIO(query[Task].filter(_.id == lift(taskId)).delete)
    } yield ()

    performIO(deletes.transactional)
  }

  def assignTask(taskId: Int, emails: Seq[String]): Future[Task] = {
    run {
      quote {
        query[Task].filter(_.id == lift(taskId)).update(_.completableBy -> lift(emails))
      }
    }.flatMap(_ => taskById(taskId))
  }

  override def requestTasks(requestSlug: String, maybeState: Option[State.State] = None): Future[Seq[(Task, DAO.NumComments)]] = {
    maybeState.fold {
      run {
        quote {
          for {
            task <- query[Task].filter(_.requestSlug == lift(requestSlug))
            numComments = query[Comment].filter(_.taskId == task.id).size
          } yield task -> numComments
        }
      }
    } { state =>
      run {
        quote {
          for {
            task <- query[Task].filter { task =>
              task.requestSlug == lift(requestSlug) && task.state == lift(state)
            }
            numComments = query[Comment].filter(_.taskId == task.id).size
          } yield task -> numComments
        }
      }
    }
  }

  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    val createDate = ZonedDateTime.now()
    run {
      quote {
        query[Comment].insert(
          _.taskId -> lift(taskId),
          _.creatorEmail -> lift(email),
          _.createDate -> lift(createDate),
          _.contents -> lift(contents)
        ).returning(_.id)
      }
    } map { id =>
      Comment(id, email, createDate, contents, taskId)
    }
  }

  override def commentsOnTask(taskId: Index): Future[Seq[Comment]] = {
    run {
      quote {
        query[Comment].filter(_.taskId == lift(taskId))
      }
    }
  }

  override def tasksForUser(email: String, state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    run {
      quote {
        for {
          task <- query[Task].filter { task =>
            task.completableBy.contains(lift(email)) &&
            task.state == lift(state)
          }

          numComments = query[Comment].filter(_.taskId == task.id).size

          request <- query[Request].filter { request =>
            request.slug == task.requestSlug &&
            request.state == lift(State.InProgress)
          }
        } yield (task, numComments, request)
      }
    }
  }

  override def search(maybeProgram: Option[String], maybeState: Option[State.State], maybeData: Option[JsObject]): Future[Seq[RequestWithTasks]] = {

    case class QueryBuilder[T](query: Quoted[Query[T]]) {
      def ifDefined[U: Encoder](value: Option[U])(f: Quoted[(Query[T], U) => Query[T]]): QueryBuilder[T] =
        value match {
          case None => this
          case Some(v) =>
            QueryBuilder {
              quote {
                f(query, lift(v))
              }
            }
        }

      def build: database.ctx.Quoted[database.ctx.Query[T]] = query
    }

    val requestQuery = QueryBuilder(query[Request])
      .ifDefined(maybeProgram) {
        (q: Query[Request], program: String) => q.filter(_.program == program)
      }
      .ifDefined(maybeState) {
        (q: Query[Request], state: State.State) => q.filter(_.state == state)
      }
      .ifDefined(maybeData) {
        (q: Query[Request], data: JsObject) => q.filter { request =>
          query[Task].filter(_.requestSlug == request.slug).filter { task =>
            infix"""${task.data} @> $data""".as[Boolean]
          }.nonEmpty
        }
      }
      .build

    run {
      quote {
        requestQuery.leftJoin(query[Task]).on(_.slug == _.requestSlug)
      }
    }.map(joinedRequestTasksToRequests)
  }

}

object DB {
  def slug(s: String): String = {
    s.toLowerCase.replaceAllLiterally("  ", " ").replaceAllLiterally(" ", "-").replaceAll("[^a-z0-9-]", "")
  }

  def nextSlug(default: String)(existingSlugs: Seq[String]): String = {
    if (existingSlugs.isEmpty) {
      default
    }
    else {
      Stream.from(1).map(default + "-" + _).dropWhile(existingSlugs.contains).head
    }
  }

  case class RequestNotFound(slug: String) extends Exception {
    override def getMessage: String = s"Request $slug not found"
  }
}

@Singleton
class DatabaseWithCtx @Inject()(lifecycle: ApplicationLifecycle, playConfig: Configuration) (implicit ec: ExecutionContext) {

  private val maybeDbUrl = playConfig.getOptional[String]("db.default.url")

  val config: db.Configuration = maybeDbUrl.map(URLParser.parse(_)).getOrElse(URLParser.DEFAULT)

  private val configWithMaybeSsl = playConfig.getOptional[String]("db.default.sslmode").fold(config) { sslmode =>
    val sslConfig = SSLConfiguration(Map("sslmode" -> sslmode))
    config.copy(ssl = sslConfig)
  }

  private val connectionFactory = new PostgreSQLConnectionFactory(configWithMaybeSsl)

  private val defaultPoolConfig = PoolConfiguration.Default

  private val maxObjects = playConfig.getOptional[Int]("db.default.max-objects").getOrElse(defaultPoolConfig.maxObjects)
  private val maxIdleMillis = playConfig.getOptional[Long]("db.default.max-idle-millis").getOrElse(defaultPoolConfig.maxIdle)
  private val maxQueueSize = playConfig.getOptional[Int]("db.default.max-queue-size").getOrElse(defaultPoolConfig.maxQueueSize)
  private val validationInterval = playConfig.getOptional[Long]("db.default.max-queue-size").getOrElse(defaultPoolConfig.validationInterval)

  private val poolConfig = new PoolConfiguration(maxObjects, maxIdleMillis, maxQueueSize, validationInterval)

  private val numberOfPartitions = playConfig.getOptional[Int]("db.default.number-of-partitions").getOrElse(4)

  private val pool = new PartitionedConnectionPool(
    connectionFactory,
    poolConfig,
    numberOfPartitions,
    ec
  )

  lifecycle.addStopHook { () =>
    pool.close
  }

  val ctx = new PostgresAsyncContext(SnakeCase, pool)

}
