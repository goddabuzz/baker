package com.ing.baker.baas.state

import java.net.InetSocketAddress
import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.stream.{ActorMaterializer, Materializer}
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import com.ing.baker.runtime.akka.{AkkaBaker, AkkaBakerConfig}
import com.ing.baker.runtime.scaladsl.Baker
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    // Config
    val config = ConfigFactory.load()
    val httpServerPort = config.getInt("baas-component.http-api-port")
    val namespace = config.getString("baas-component.kubernetes-namespace")

    // Core dependencies
    implicit val system: ActorSystem =
      ActorSystem("BaaSStateNodeSystem")
    implicit val materializer: Materializer =
      ActorMaterializer()
    implicit val blockingEC: ExecutionContext =
      ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    val connectionPool: ExecutionContext =
      ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    val hostname: InetSocketAddress =
      InetSocketAddress.createUnresolved("0.0.0.0", httpServerPort)

    val mainResource = for {
      serviceDiscovery <- ServiceDiscovery.resource(connectionPool, namespace)
      baker: Baker = AkkaBaker.withConfig(AkkaBakerConfig(
          interactionManager = serviceDiscovery.buildInteractionManager,
          bakerActorProvider = AkkaBakerConfig.bakerProviderFrom(config),
          readJournal = AkkaBakerConfig.persistenceQueryFrom(config, system),
          timeouts = AkkaBakerConfig.Timeouts.from(config),
          bakerValidationSettings = AkkaBakerConfig.BakerValidationSettings.from(config)
        )(system))
      _ <- StateNodeService.resource(baker, hostname)
    } yield ()

    IO(Cluster(system).registerOnMemberUp {
      mainResource.use(_ => IO.never).unsafeRunAsyncAndForget()
    }).as(ExitCode.Success)
  }
}
