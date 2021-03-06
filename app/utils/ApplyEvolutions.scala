/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package utils

import models.Task.CompletableByType
import modules.DatabaseWithCtx
import play.api.db.DBApi
import play.api.db.evolutions.{EvolutionsApi, EvolutionsReader}
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.{Application, Logger, Mode}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.io.StdIn
import scala.util.Try

object ApplyEvolutions extends App {
  val app = new GuiceApplicationBuilder().in(Mode.Prod).configure(Map("play.evolutions.db.default.enabled" -> false)).build()

  Try(new ApplyEvolutions(app).run)

  app.stop()
}

class ApplyEvolutions(app: Application) {
  def run: Unit = {
    implicit val ec = app.injector.instanceOf[ExecutionContext]

    val dbApi = app.injector.instanceOf[DBApi]

    val evolutionsApi = app.injector.instanceOf[EvolutionsApi]
    val evolutionsReader = app.injector.instanceOf[EvolutionsReader]

    val scripts = evolutionsApi.scripts("default", evolutionsReader, "")

    val migrations = Set(Migration(2, migration2))

    scripts.foreach { script =>
      Logger.info(s"Applying evolution ${script.evolution.revision}")
      evolutionsApi.evolve("default", Seq(script), true, "")

      migrations.filter(_.after == script.evolution.revision).foreach { migration =>
        Logger.info(s"Applying manual migration ${migration.after}")
        migration.migrator()
      }
    }
  }

  case class Migration(after: Int, migrator: () => Unit)

  val migration2 = () => {
    implicit val ec = app.injector.instanceOf[ExecutionContext]

    val metadataService = app.injector.instanceOf[MetadataService]
    val metadata = Await.result(metadataService.fetchMetadata, Duration.Inf)

    val databaseWithCtx = app.injector.instanceOf[DatabaseWithCtx]
    import databaseWithCtx.ctx._

    val requestsQuery = databaseWithCtx.ctx.run {
      quote {
        infix"SELECT slug FROM request".as[Query[String]]
      }
    }

    val requests = Await.result(requestsQuery, Duration.Inf)

    requests.foreach { slug =>
      val tasksQuery = databaseWithCtx.ctx.run {
        quote {
          infix"""SELECT id AS "_1", completable_by_type AS "_2", completable_by_value AS "_3", completable_by AS "_4", prototype->>'label' AS "_5" FROM task WHERE request_slug = ${lift(slug)}""".as[Query[(Int, CompletableByType.CompletableByType, String, Seq[String], String)]]
        }
      }

      val tasks = Await.result(tasksQuery, Duration.Inf)

      tasks.foreach { case (id, completableByType, completableByValue, completableBy, label) =>
        if (completableBy.isEmpty) {
          Logger.info(s"Migrating task '$label' on request '$slug'")

          val maybeAssignTo = completableByType match {
            case CompletableByType.Group => metadata.programs("default").groups.get(completableByValue).map(_.toSeq)
            case CompletableByType.Email => Some(Seq(completableByValue))
            case _ => None
          }

          val assignTo = maybeAssignTo.getOrElse {
            StdIn.readLine("Assign task '$label' on request '$slug' to emails (comma separated): \n").replaceAllLiterally(" ", "").split(",").toSeq
          }

          val updateTask = databaseWithCtx.ctx.run {
            quote {
              infix"UPDATE task SET completable_by = ${lift(assignTo)} WHERE id = ${lift(id)}".as[Update[Long]]
            }
          }

          Await.result(updateTask, Duration.Inf)
          Logger.info(s"Done with task '$label' on request '$slug'")
        }
      }
    }
  }

}
