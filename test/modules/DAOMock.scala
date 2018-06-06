/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package modules

import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

import javax.inject.Singleton
import models.Task.CompletableByType
import models.{Comment, Request, State, Task}
import play.api.Mode
import play.api.db.evolutions.EvolutionsModule
import play.api.db.{DBModule, HikariCPModule}
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.JsObject

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try

@Singleton
class DAOMock extends DAO {
  val requests = ConcurrentHashMap.newKeySet[Request].asScala.toBuffer
  val tasks = ConcurrentHashMap.newKeySet[Task].asScala.toBuffer
  val comments = ConcurrentHashMap.newKeySet[Comment].asScala.toBuffer

  override def createRequest(name: String, creatorEmail: String): Future[Request] = {
    Future.successful {
      val request = Request(DB.slug(name), name, ZonedDateTime.now(), creatorEmail, State.InProgress, None)
      requests += request
      request
    }
  }

  override def allRequests(): Future[Seq[(Request, DAO.NumTotalTasks, DAO.NumCompletedTasks)]] = {
    Future.sequence {
      requests.map { request =>
        requestTasks(request.slug).map { requestTasks =>
          val numCompletedTasks = requestTasks.map(_._1).count(_.state == State.Completed).toLong
          (request, requestTasks.size.toLong, numCompletedTasks)
        }
      }
    }
  }

  override def requestsForUser(email: String): Future[Seq[(Request, DAO.NumTotalTasks, DAO.NumCompletedTasks)]] = {
    allRequests().map(_.filter(_._1.creatorEmail == email))
  }

  override def updateTask(taskId: Int, state: State.State, maybeCompletedBy: Option[String], maybeData: Option[JsObject]): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found"))) { task =>
      val updatedTask = task.copy(
        state = state,
        completedByEmail = maybeCompletedBy,
        data = maybeData
      )

      tasks -= task
      tasks += updatedTask

      Future.successful(updatedTask)
    }
  }

  override def commentOnTask(taskId: Int, email: String, contents: String): Future[Comment] = {
    Future.successful {
      val id = Try(comments.map(_.id).max).getOrElse(0) + 1
      val comment = Comment(id, email, ZonedDateTime.now(), contents, taskId)
      comments += comment
      comment
    }
  }

  override def request(requestSlug: String): Future[Request] = {
    requests.find(_.slug == requestSlug).fold(Future.failed[Request](new Exception("Request not found")))(Future.successful)
  }

  override def updateRequest(requestSlug: String, state: State.State): Future[Request] = {
    request(requestSlug).map { request =>
      val updatedRequest = request.copy(state = state)
      requests -= request
      requests += request
      updatedRequest
    }
  }

  override def createTask(requestSlug: String, prototype: Task.Prototype, completableByType: CompletableByType.CompletableByType, completableByValue: String, maybeCompletedBy: Option[String], maybeData: Option[JsObject], state: State.State): Future[Task] = {
    Future.successful {
      val id = Try(tasks.map(_.id).max).getOrElse(0) + 1
      val task = Task(id, completableByType, completableByValue, maybeCompletedBy, maybeCompletedBy.map(_ => ZonedDateTime.now()), state, prototype, maybeData, requestSlug)
      tasks += task
      task
    }
  }

  override def taskById(taskId: Int): Future[Task] = {
    tasks.find(_.id == taskId).fold(Future.failed[Task](new Exception("Task not found")))(Future.successful)
  }

  override def requestTasks(requestSlug: String, maybeState: Option[State.State]): Future[Seq[(Task, DAO.NumComments)]] = {
    val requestTasks = tasks.filter(_.requestSlug == requestSlug)

    val requestTasksWithMaybeState = maybeState.fold(requestTasks) { state =>
      requestTasks.filter(_.state == state)
    }

    Future.sequence {
      requestTasksWithMaybeState.map { task =>
        commentsOnTask(task.id).map { comments =>
          task -> comments.size.toLong
        }
      }
    }
  }
  override def commentsOnTask(taskId: Int): Future[Seq[Comment]] = {
    Future.successful(comments.filter(_.taskId == taskId))
  }

  override def tasksForUser(email: String, state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    Future.successful {
      tasks.filter { task =>
        task.completableByType == CompletableByType.Email &&
        task.completableByValue == email &&
        task.state == state
      } flatMap { task =>
        val numTaskComments = comments.count(_.taskId == task.id).toLong
        requests.find(_.slug == task.requestSlug).map { request =>
          (task, numTaskComments, request)
        }
      }
    }
  }

  override def tasksForGroups(groups: Set[String], state: State.State): Future[Seq[(Task, DAO.NumComments, Request)]] = {
    Future.successful {
      tasks.filter { task =>
        task.completableByType == CompletableByType.Group &&
        groups.contains(task.completableByValue) &&
        task.state == state
      } flatMap { task =>
        val numTaskComments = comments.count(_.taskId == task.id).toLong
        requests.find(_.slug == task.requestSlug).map { request =>
          (task, numTaskComments, request)
        }
      }
    }
  }
}

object DAOMock {

  val daoUrl = sys.env.getOrElse("DATABASE_URL", "postgres://ossrequest:password@localhost:5432/ossrequest-test")

  val testConfig = Map("db.default.url" -> daoUrl)

  // has a daomodule connected to the test db
  def databaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = new GuiceApplicationBuilder()
    .configure(testConfig)
    .configure(additionalConfig)
    .disable[EvolutionsModule]
    .in(mode)

  // has no real dao
  def noDatabaseAppBuilder(mode: Mode = Mode.Test, additionalConfig: Map[String, Any] = Map.empty[String, Any]) = databaseAppBuilder(mode, additionalConfig)
    .disable[HikariCPModule]
    .disable[DBModule]
    .overrides(bind[DAO].to[DAOMock])

}
