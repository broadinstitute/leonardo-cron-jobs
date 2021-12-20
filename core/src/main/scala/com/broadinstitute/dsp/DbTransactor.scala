package com.broadinstitute.dsp

import cats.effect.{Async, Resource}
import doobie.ExecutionContexts
import doobie.hikari.HikariTransactor

object DbTransactor {
  def init[F[_]: Async](databaseConfig: DatabaseConfig): Resource[F, HikariTransactor[F]] =
    for {
      fixedThreadPool <- ExecutionContexts.fixedThreadPool(100)
      cachedThreadPool <- ExecutionContexts.cachedThreadPool
      xa <- HikariTransactor.newHikariTransactor[F](
        "com.mysql.cj.jdbc.Driver", // driver classname
        databaseConfig.url,
        databaseConfig.user,
        databaseConfig.password,
        fixedThreadPool
      )
    } yield xa
}

final case class DatabaseConfig(url: String, user: String, password: String)
