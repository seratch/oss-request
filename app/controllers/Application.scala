/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package controllers

import javax.inject.Inject
import models.Task.CompletableByType
import models.{State, Task}
import modules.{Auth, DAO, DB, NotifyProvider}
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc._
import play.api.{Configuration, Environment, Logger, Mode}
import play.twirl.api.Html
import utils.{DataFacade, MetadataService, Program, RuntimeReporter, UserAction, UserInfo, UserRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dataFacade: DataFacade, userAction: UserAction, auth: Auth, metadataService: MetadataService, configuration: Configuration, webJarsUtil: WebJarsUtil, notifyProvider: NotifyProvider, runtimeReporter: RuntimeReporter, dao: DAO)
  (requestsView: views.html.Requests, newRequestView: views.html.NewRequest, newRequestFormView: views.html.NewRequestForm, requestView: views.html.Request, commentsView: views.html.partials.Comments, formTestView: views.html.FormTest, notifyTestView: views.html.NotifyTest, loginView: views.html.Login, pickEmailView: views.html.PickEmail, errorView: views.html.Error, openUserTasksView: views.html.OpenUserTasks, taskView: views.html.Task, searchView: views.html.Search)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  private[controllers] def completableByWithDefaults(maybeCompletableBy: Option[Task.CompletableBy], maybeRequestOwner: Option[String], maybeProvidedValue: Option[String]): Option[(CompletableByType.CompletableByType, String)] = {
    (maybeCompletableBy, maybeRequestOwner, maybeProvidedValue) match {
      case (Some(Task.CompletableBy(completableByType, Some(completableByValue))), _, _) => Some(completableByType -> completableByValue)
      case (Some(Task.CompletableBy(completableByType, None)), _, Some(providedValue)) => Some(completableByType -> providedValue)
      case (None, Some(requestOwner), _) => Some(CompletableByType.Email -> requestOwner)
      case _ => None
    }
  }

  private def withUserInfo[A](f: UserInfo => Future[Result])(implicit userRequest: UserRequest[A]): Future[Result] = {
    userRequest.maybeUserInfo.fold(auth.authUrl.map(Redirect(_)))(f)
  }

  def index = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        dataFacade.tasksForUser(userInfo.email, State.InProgress).map { tasks =>
          if (tasks.isEmpty) {
            Redirect(routes.Application.requests(None))
          }
          else {
            Redirect(routes.Application.openUserTasks())
          }
        }
      }
    }
  }

  def openUserTasks = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        dataFacade.tasksForUser(userInfo.email, State.InProgress).map { tasks =>
          val tasksWithProgram = tasks.flatMap { case (task, numComments, request) =>
            metadata.programs.get(request.program).map { program =>
              (task, numComments, request, program)
            }
          }

          // todo: this could be better
          if (tasks.size == tasksWithProgram.size) {
            Ok(openUserTasksView(tasksWithProgram, userInfo))
          }
          else {
            InternalServerError("Could not find a specified program")
          }
        }
      }
    }
  }

  def requests(program: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>

        val (maybeTitle, requestsFuture) = program.map { programKey =>
          metadata.programs.get(programKey).map(_.name) -> dataFacade.programRequests(programKey)
        } getOrElse {
          Some("Your Requests") -> dataFacade.userRequests(userInfo.email)
        }

        maybeTitle.map { title =>
          requestsFuture.map { requests =>
            Ok(requestsView(title, requests, userInfo))
          }
        } getOrElse {
          Future.successful(InternalServerError(errorView("Could not find program", userInfo)))
        }
      }
    }
  }

  def search(program: Option[String], state: Option[models.State.State], data: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        val maybeData = data.flatMap(Json.parse(_).asOpt[JsObject])
        dataFacade.search(program, state, maybeData).map { requests =>
          Ok(searchView(requests, userInfo))
        }
      }
    }
  }

  def newRequest(maybeName: Option[String], maybeProgramKey: Option[String], maybeStartTask: Option[String]) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        val nonEmptyMaybeName = maybeName.filter(_.nonEmpty)
        val nonEmptyMaybeProgramKey = maybeProgramKey.filter(_.nonEmpty)
        val nonEmptyMaybeStartTask = maybeStartTask.filter(_.nonEmpty)

        val maybeTaskView = for {
          name <- nonEmptyMaybeName
          programKey <- nonEmptyMaybeProgramKey
          programMetadata <- metadata.programs.get(programKey)
          startTask <- nonEmptyMaybeStartTask
          task <- programMetadata.tasks.get(startTask)
        } yield {
          dataFacade.requestsSimilarToName(programKey, name).map { similarRequests =>
            Ok(newRequestFormView(programKey, name, startTask, task, userInfo, similarRequests))
          }
        }

        maybeTaskView.getOrElse(Future.successful(Ok(newRequestView(userInfo, nonEmptyMaybeName, nonEmptyMaybeProgramKey, nonEmptyMaybeStartTask))))
      }
    }
  }

  def createRequest(name: String, programKey: String, startTask: String) = userAction.async(parse.json) { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        metadata.programs.get(programKey).fold {
          Future.successful(NotFound(s"Program '$programKey' not found"))
        } { programMetadata =>
          programMetadata.tasks.get(startTask).fold(Future.successful(InternalServerError(s"Could not find task named '$startTask'"))) { metaTask =>
            dataFacade.createRequest(programKey, name, userInfo.email).flatMap { request =>
              completableByWithDefaults(metaTask.completableBy, Some(userInfo.email), None).flatMap(programMetadata.completableBy).fold {
                Future.successful(BadRequest("Could not determine who can complete the task"))
              } { emails =>
                dataFacade.createTask(request.slug, metaTask, emails.toSeq, Some(userInfo.email), Json.toJson(userRequest.body).asOpt[JsObject], State.Completed).map { task =>
                  Ok(Json.toJson(request))
                }
              }
            }
          }
        }
      }
    }
  }

  def request(requestSlug: String) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
          metadata.programs.get(request.program).fold {
            Future.successful(InternalServerError(s"Could not find program ${request.program}"))
          } { program =>
            dataFacade.requestTasks(userInfo.email, request.slug).map { tasks =>
              Ok(requestView(program, request, tasks, userInfo))
            }
          }
        } recover {
          case rnf: DB.RequestNotFound => NotFound(errorView(rnf.getMessage, userInfo))
        }
      }
    }
  }

  def updateRequest(requestSlug: String, state: State.State) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      // todo: allow user to provide a message
      dataFacade.updateRequest(userInfo.email, requestSlug, state, None).map { request =>
        Redirect(routes.Application.request(request.slug))
      }
    }
  }

  def task(requestSlug: String, taskId: Int) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        val f = for {
          request <- dataFacade.request(userInfo.email, requestSlug)
          task <- dataFacade.taskById(taskId)
          comments <- dataFacade.commentsOnTask(taskId)
          program <- metadata.programs.get(request.program).fold(Future.failed[Program](new Exception("Program not found")))(Future.successful)
        } yield Ok(taskView(request, task, comments, userInfo, program.isAdmin(userInfo), program.groups.keySet))

        f.recover {
          case e: Exception =>
            InternalServerError(errorView(e.getMessage, userInfo))
        }
      }
    }
  }

  def addTask(requestSlug: String) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        val maybeTaskPrototypeKey = userRequest.body.get("taskPrototypeKey").flatMap(_.headOption)
        val maybeCompletableBy = userRequest.body.get("completableBy").flatMap(_.headOption).filterNot(_.isEmpty)

        dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
          maybeTaskPrototypeKey.fold(Future.successful(BadRequest("No taskPrototypeKey specified"))) { taskPrototypeKey =>
            val maybeTask = for {
              programMetadata <- metadata.programs.get(request.program)
              task <- programMetadata.tasks.get(taskPrototypeKey)
              completableBy <- completableByWithDefaults(task.completableBy, Some(request.creatorEmail), maybeCompletableBy).flatMap(programMetadata.completableBy)
            } yield (task, completableBy)

            maybeTask.fold(Future.successful(InternalServerError(s"Could not find task prototype $taskPrototypeKey"))) { case (task, completableBy) =>
              dataFacade.createTask(requestSlug, task, completableBy.toSeq).map { _ =>
                Redirect(routes.Application.request(request.slug))
              } recover {
                case e @ (_: DataFacade.DuplicateTaskException | _: DataFacade.MissingTaskDependencyException) =>
                  BadRequest(errorView(e.getMessage, userInfo))
              }
            }
          }
        }
      }
    }
  }

  private lazy val maybeJsObject: BodyParser[Option[JsObject]] = {
    parse.tolerantText.map { s =>
      Try(Json.parse(s).as[JsObject]).toOption
    }
  }

  def updateTaskState(requestSlug: String, taskId: Int, state: State.State, completionMessage: Option[String]) = userAction.async(maybeJsObject) { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.updateTaskState(userInfo.email, taskId, state, Some(userInfo.email), userRequest.body, completionMessage).map { task =>
        render {
          case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
          case Accepts.Json() => Ok(Json.toJson(task))
        }
      }
    }
  }

  def updateTaskAssignment(requestSlug: String, taskId: Int) = userAction.async(parse.json) { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { metadata =>
        dataFacade.request(userInfo.email, requestSlug).flatMap { request =>
          val maybeCompletableBy = (userRequest.body \ "email").asOpt[String].map { email =>
            CompletableByType.Email -> email
          } orElse {
            (userRequest.body \ "group").asOpt[String].map { group =>
              CompletableByType.Group -> group
            }
          } flatMap { case (completableByType, completableByValue) =>
            metadata.programs.get(request.program).flatMap { program =>
              program.completableBy(completableByType, completableByValue)
            }
          }

          maybeCompletableBy.fold(Future.successful(BadRequest("Must specify an email or group"))) { emails =>
            dataFacade.assignTask(userInfo.email, taskId, emails.toSeq).map { _ =>
              Ok
            }
          }
        }
      }
    }
  }

  def deleteTask(requestSlug: String, taskId: Int) = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.deleteTask(userInfo.email, taskId).map { _ =>
        render {
          case Accepts.Html() => Redirect(routes.Application.request(requestSlug))
          case Accepts.Json() => Ok
        }
      }
    }
  }

  def commentOnTask(requestSlug: String, taskId: Int) = userAction.async(parse.formUrlEncoded) { implicit userRequest =>
    withUserInfo { userInfo =>
      val maybeContents = userRequest.body.get("contents").flatMap(_.headOption).filterNot(_.isEmpty)
      val maybeRedirect = userRequest.body.get("redirect").flatMap(_.headOption)

      maybeContents.fold(Future.successful(BadRequest("The contents were empty"))) { contents =>
        dataFacade.commentOnTask(requestSlug, taskId, userInfo.email, contents).map { comment =>
          Redirect {
            maybeRedirect.getOrElse(routes.Application.request(requestSlug).url)
          }
        }
      }
    }
  }

  def commentsOnTask(requestSlug: String, taskId: Int) = userAction.async(maybeJsObject) { implicit userRequest =>
    withUserInfo { userInfo =>
      dataFacade.commentsOnTask(taskId).map { comments =>
        Ok(commentsView(comments))
      }
    }
  }

  def emailReply = Action.async(parse.form(notifyProvider.form)) { implicit request =>

    val maybeRequestSlugAndCommentId = for {
      requestSlug <- (request.body.data \ "request-slug").asOpt[String]
      taskId <- (request.body.data \ "task-id").asOpt[Int]
    } yield (requestSlug, taskId)

    maybeRequestSlugAndCommentId.fold {
      val emailBody = "Sorry, but we couldn't figure out what to do with your email:\n\n" + request.body.body
      notifyProvider.sendMessage(Set(request.body.sender), "OSS Request Email Not Handled", emailBody).map { _ =>
        NotAcceptable
      }
    } { case (requestSlug, taskId) =>
      dataFacade.commentOnTask(requestSlug, taskId, request.body.sender, request.body.body).map { _ =>
        Ok
      }
    }
  }

  def formTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.map { implicit metadata =>
        Ok(formTestView(userInfo))
      }
    }
  }

  def notifyTest = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.map { implicit metadata =>
        Ok(notifyTestView(userInfo))
      }
    }
  }

  def notifyTestSend = userAction.async { implicit userRequest =>
    withUserInfo { userInfo =>
      metadataService.fetchMetadata.flatMap { implicit metadata =>
        val maybeInfo = for {
          form <- userRequest.body.asFormUrlEncoded

          recipients <- form.get("recipient")
          recipient <- recipients.headOption
          if !recipient.isEmpty

          messages <- form.get("message")
          message <- messages.headOption
          if !message.isEmpty
        } yield recipient -> message

        maybeInfo.fold {
          Future.successful(BadRequest(notifyTestView(userInfo, Some(Failure(new Exception("Missing form value"))))))
        } { case (recipient, message) =>
          notifyProvider.sendMessage(Set(recipient), "Notify Test", message).map { result =>
            val message = result match {
              case s: String => s
              case _ => "Test Successful"
            }
            Ok(notifyTestView(userInfo, Some(Success(message))))
          } recover {
            case t: Throwable => Ok(notifyTestView(userInfo, Some(Failure(t))))
          }
        }
      }
    }
  }

  private def login(state: Option[String])(emails: Set[String])(implicit request: RequestHeader): Future[Result] = {
    if (emails.size > 1) {
      metadataService.fetchMetadata.map { implicit  metadata =>
        Ok(pickEmailView(emails, state)).withSession("emails" -> emails.mkString(","))
      }
    }
    else if (emails.size == 1) {
      val email = emails.head

      metadataService.fetchMetadata.map { metadata =>
        val url = state.getOrElse(controllers.routes.Application.openUserTasks().url)

        // todo: putting this info in the session means we can't easily invalidate it later
        Redirect(url).withSession("email" -> email)
      }
    }
    else {
      Future.successful(BadRequest("Could not determine user email"))
    }
  }

  def callback(code: Option[String], state: Option[String]) = Action.async { implicit request =>
    auth.emails(code).flatMap(login(state)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def acs() = Action.async(parse.formUrlEncoded) { implicit request =>
    val maybeState = request.body.get("RelayState").flatMap(_.headOption)
    auth.emails(request.body.get("SAMLResponse").flatMap(_.headOption)).flatMap(login(maybeState)).recover {
      case e: Exception => Unauthorized(e.getMessage)
    }
  }

  def selectEmail(email: String, state: Option[String]) = Action { request =>
    val maybeValidEmail = request.session.get("emails").map(_.split(",")).getOrElse(Array.empty[String]).find(_ == email)
    maybeValidEmail.fold(Unauthorized("Email invalid")) { validEmail =>
      val url = state.getOrElse(controllers.routes.Application.openUserTasks().url)
      // todo: putting this info in the session means we can't easily invalidate it later
      Redirect(url).withSession("email" -> validEmail)
    }
  }

  def logout() = Action.async { implicit request =>
    auth.authUrl.flatMap { authUrl =>
      metadataService.fetchMetadata.map { implicit metadata =>
        Ok(loginView()).withNewSession
      }
    }
  }

  private[controllers] def demoRepoAllowed(request: RequestHeader): Boolean = {
    configuration.getOptional[String]("services.repo_creator").fold(true) { psk =>
      request.headers.get(AUTHORIZATION).fold(false)(_ == s"psk $psk")
    }
  }

  def createDemoRepo() = Action(parse.json) { request =>
    val allowed = demoRepoAllowed(request)

    env.mode match {
      case Mode.Prod =>
        NotFound
      case _ if !allowed =>
        Unauthorized
      case _ =>
        Logger.info(request.body.toString)

        val json = Json.obj(
          "state" -> State.InProgress,
          "url" -> "http://asdf.com"
        )

        Created(json)
    }
  }

  // one minute after the task is created the status is switched to Completed
  def demoRepo(url: String) = Action { implicit request =>
    val allowed = demoRepoAllowed(request)

    env.mode match {
      case Mode.Prod =>
        NotFound
      case _ if !allowed =>
        Unauthorized
      case _ =>
        val json = Json.obj(
          "state" -> State.Completed,
          "url" -> "http://asdf.com",
          "data" -> Json.obj(
            "message" -> "Repo created!"
          )
        )

        Ok(json)
    }
  }

  private[controllers] def svgSymbol(path: String, symbol: String): Node = {
    webJarsUtil.locate(path).path.flatMap { filePath =>
      val maybeInputStream = env.resourceAsStream(WebJarAssetLocator.WEBJARS_PATH_PREFIX + "/" + filePath)
      maybeInputStream.fold[Try[Node]](Failure(new Exception("Could not read file"))) { inputStream =>
        val elem = scala.xml.XML.load(inputStream)
        inputStream.close()

        val maybeSymbol = elem.child.find { node =>
          node \@ "id" == symbol
        } flatMap (_.child.headOption)

        maybeSymbol.fold[Try[Node]](Failure(new Exception(s"Could not find symbol $symbol")))(Success(_))
      }
    } fold (
      { t => Comment(s"Error getting SVG: ${t.getMessage}") },
      { identity }
    )
  }

  private def svgInline(path: String, symbol: String): Html = {
    Html(svgSymbol(path, symbol).toString())
  }

}
