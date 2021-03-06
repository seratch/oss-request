/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import java.time.ZonedDateTime

import models.{Request, State, Task}
import modules.{DAO, DAOMock}
import org.scalatestplus.play.MixedPlaySpec
import play.api.Application
import play.api.libs.json.JsObject
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class TaskServiceSpec extends MixedPlaySpec {

  def taskService(implicit app: Application) = app.injector.instanceOf[TaskService]
  def metadataService(implicit app: Application) = app.injector.instanceOf[MetadataService]
  def program(implicit app: Application) = await(metadataService.fetchMetadata).programs("two")
  implicit val fakeRequest = FakeRequest()

  def updateTaskState(task: Task)(state: State.State, maybeUrl: Option[String], maybeData: Option[JsObject], maybeCompletionMessage: Option[String]): Future[Task] = {
    Future.successful {
      task.copy(state = state, completedBy = maybeUrl, data = maybeData, completionMessage = maybeCompletionMessage)
    }
  }

  "taskCreated" must {
    "not do anything if the task isn't assign to a service" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None, None)
      val taskPrototype = program.tasks("oss_request_info")
      val task = Task(1, ZonedDateTime.now(), Seq("asdf@asdf.com"), None, None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskState(task)))
      updatedTask must equal (task)
    }
    "set the task to cancelled if it is assigned to a service that is unreachable" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None, None)
      val taskPrototype = program.tasks("create_repo")
      val task = Task(1, ZonedDateTime.now(), Seq("http://localhost:12345/"), None, None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskState(task)))
      updatedTask.state must equal (State.Cancelled)
    }
    "set the task to cancelled if the service does not respond with the correct json" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None, None)
      val taskPrototype = program.tasks("create_repo")
      val url = "https://echo-webhook.herokuapp.com/asdf"
      val task = Task(1, ZonedDateTime.now(), Seq(url), None, None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskState(task)))
      updatedTask.state must equal (State.Cancelled)

    }
    "update the task when the server responds correctly" in new Server(DAOMock.noDatabaseAppBuilder().build()) {
      val request = Request("two", "asdf", "asdf", ZonedDateTime.now(), "asdf@asdf.com", State.InProgress, None, None)
      val taskPrototype = program.tasks("create_repo")
      val url = controllers.routes.Application.createDemoRepo().absoluteURL(false, s"localhost:$port")
      val task = Task(1, ZonedDateTime.now(), Seq(url), None, None, None, State.InProgress, taskPrototype, None, request.slug)
      val updatedTask = await(taskService.taskCreated(program, request, task, Seq.empty[Task], "http://asdf.com", updateTaskState(task)))
      updatedTask.completedBy must equal (Some("http://asdf.com"))
    }
  }

  "taskStatus" must {
    "set the task to be cancelled when the external url is not set" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val taskPrototype = program.tasks("create_repo")
      val task = Task(1, ZonedDateTime.now(), Seq("http://localhost:12345/"), None, None, None, State.InProgress, taskPrototype, None, "asdf")
      val updatedTask = await(taskService.taskStatus(task, updateTaskState(task)))
      updatedTask.state must equal (State.Cancelled)
    }
    "set the task to cancelled when the service is unreachable" in new App(DAOMock.noDatabaseAppBuilder().build()) {
      val taskPrototype = program.tasks("create_repo")
      val task = Task(1, ZonedDateTime.now(), Seq("http://localhost:12345/"), Some("http://asdf.com"), None, None, State.InProgress, taskPrototype, None, "asdf")
      val updatedTask = await(taskService.taskStatus(task, updateTaskState(task)))
      updatedTask.state must equal (State.Cancelled)
    }
    "work when the task exists" in new Server(DAOMock.noDatabaseAppBuilder().build()) {
      val taskPrototype = program.tasks("create_repo")
      val dao = app.injector.instanceOf[DAO]
      val url = controllers.routes.Application.createDemoRepo().absoluteURL(false, s"localhost:$port")
      val task = await(dao.createTask("asdf", taskPrototype, Seq(url), Some("http://asdf.com/asdf")))
      val updatedTask = await(taskService.taskStatus(task, updateTaskState(task)))
      updatedTask.completedBy must equal (Some("http://asdf.com"))
    }
  }

}
