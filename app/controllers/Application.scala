/*
 * Copyright (c) Salesforce.com, inc. 2017
 */

package controllers

import javax.inject.Inject

import models.State
import models.Task.CompletableByType
import modules.DAO
import org.webjars.WebJarAssetLocator
import org.webjars.play.WebJarsUtil
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.Results.EmptyContent
import play.api.mvc._
import play.api.{Configuration, Environment, Mode}
import play.twirl.api.Html
import utils.dev.DevUsers
import utils.{MetadataService, Oauth, UserAction}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Comment, Node}


class Application @Inject()
  (env: Environment, dao: DAO, userAction: UserAction, oauth: Oauth, metadataService: MetadataService, configuration: Configuration, webJarsUtil: WebJarsUtil, devUsers: DevUsers)
  (indexView: views.html.Index, devSelectUserView: views.html.dev.SelectUser, newRequestView: views.html.NewRequest, taskView: views.html.Task)
  (implicit ec: ExecutionContext)
  extends InjectedController {

  def index = userAction.async { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      val requestsFuture = if (userInfo.isAdmin) {
        dao.allRequests()
      }
      else {
        dao.requestsForUser(userInfo.email)
      }

      requestsFuture.map { requests =>
        Ok(indexView(requests, userInfo))
      }
    }
  }

  def newRequest(maybeName: Option[String]) = userAction.async { implicit userRequest =>
    maybeName.fold {
      Future.successful(Ok(newRequestView()))
    } { name =>
      metadataService.fetchMetadata.map { metadata =>
        metadata.tasks.get("start").fold(InternalServerError("Could not find task named 'start'")) { metaTask =>
          val submitJson = Json.obj(
            "options" -> Json.obj(
              "form" -> Json.obj(
                "attributes" -> Json.obj(
                  "action" -> routes.Application.newRequest(Some(name)).url,
                  "method" -> "post"
                ),
                "buttons" -> Json.obj(
                  "submit" -> Json.obj()
                )
              )
            )
          )
          val formWithSubmit = metaTask.form.map(_ deepMerge submitJson)
          val metaTaskWithSubmit = metaTask.copy(form = formWithSubmit)
          Ok(taskView(name, metaTaskWithSubmit))
        }
      }
    }
  }

  def createRequest(name: String) = userAction.async(parse.json) { implicit userRequest =>
    userRequest.maybeUserInfo.fold(Future.successful(Redirect(oauth.authUrl))) { userInfo =>
      metadataService.fetchMetadata.flatMap { metadata =>
        metadata.tasks.get("start").fold(Future.successful(InternalServerError("Could not find task named 'start'"))) { metaTask =>
          dao.createRequest(name, userInfo.email).flatMap { projectRequest =>
            dao.createTask(projectRequest.id, metaTask, CompletableByType.Email, userInfo.email, Json.toJson(userRequest.body).asOpt[JsObject], State.Completed).map { task =>
              // todo: send redirect?
              Ok(Json.toJson(projectRequest))
            }
          }
        }
      }
    }
  }

  def addTaskToRequest = Action {
    // must be admin
    // email completable_by_email and request owner

    NotImplemented
  }

  def updateRequest = Action {
    // must be admin
    // notify request owner

    NotImplemented
  }

  def updateTask = Action {
    // admins and completable_by_email user are allowed
    // email request owner (if that isn't who is logged in)

    NotImplemented
  }

  def addCommentToTask = Action {
    // protected by oauth
    // must be admin, request owner, or completable_by_email user
    // email task completable_by_email or request owner (depending on who is commenting)

    NotImplemented
  }

  def oauthCallback(code: String, state: Option[String]) = Action.async { implicit request =>
    oauth.accessToken(oauth.tokenUrl(code)).flatMap { accessToken =>
      oauth.email(oauth.userinfoUrl(), accessToken).flatMap { email =>
        metadataService.fetchMetadata.map { metadata =>
          val isAdmin = metadata.groups("admin").contains(email)
          val url = state.getOrElse(controllers.routes.Application.index().url)

          // todo: putting this info in the session means we can't easily invalidate it later
          Redirect(url).withSession("email" -> email, "isAdmin" -> isAdmin.toString)
        }
      }
    }
  }


  def devOauthAuthorize(response_type: String, client_id: String, redirect_uri: String) = Action { implicit request =>
    env.mode match {
      case Mode.Prod => Unauthorized
      case _ => Ok(devSelectUserView(request))
    }
  }

  def devOauthUserinfo = Action { implicit request =>
    env.mode match {
      case Mode.Prod =>
        Unauthorized
      case _ =>
        val maybeToken = request.headers.get(AUTHORIZATION).map(_.stripPrefix("Bearer "))

        val maybeUser = maybeToken.flatMap { token =>
          devUsers.users.find(_.token == token)
        }

        maybeUser.fold(Unauthorized("User not found")) { user =>
          val json = Json.obj(
            "email" -> user.email
          )
          Ok(json)
        }
    }
  }


  def devOauthToken(grant_type: String, code: String, redirect_uri: String, client_id: String, client_secret: String) = Action {
    val json = Json.obj(
      "access_token" -> code
    )

    env.mode match {
      case Mode.Prod => Unauthorized
      case _ => Ok(json)
    }
  }

  def devMetadata = Action {
    env.mode match {
      case Mode.Prod =>
        Unauthorized
      case _ =>
        env.getExistingFile(metadataService.defaultMetadataFile).fold(InternalServerError(s"${metadataService.defaultMetadataFile} not found")) { metadataFile =>
          Ok.sendFile(metadataFile)
        }
    }
  }

  def wellKnown(key: String) = Action {
    configuration.getOptional[String]("wellknown").fold(NotFound(EmptyContent())) { wellKnownKeyValue =>
      if (wellKnownKeyValue.startsWith(key + "=")) {
        Ok(wellKnownKeyValue.stripPrefix(key + "="))
      }
      else {
        NotFound(EmptyContent())
      }
    }
  }

  private[controllers] def svgSymbol(path: String, symbol: String): Node = {
    webJarsUtil.locate(path).flatMap { filePath =>
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
