package filodb.coordinator

import scala.collection.mutable.{ArrayBuffer, HashMap}
import scala.concurrent.ExecutionContext
import scala.util.Random

import akka.actor.{ActorContext, ActorRef, Address}
import akka.cluster.ClusterEvent.{MemberEvent, MemberRemoved, MemberUp}
import com.typesafe.scalalogging.StrictLogging

import filodb.coordinator.ActorName.nodeCoordinatorPath
import filodb.core.DatasetRef

final case class NodeCoordinatorActorDiscovered(addr: Address, coordinator: ActorRef)

/**
  * To handle query routing from a NodeCoordActor.
  * @param settings the filodb settings.
  */
class TempQueryRouter(settings: FilodbSettings) extends StrictLogging {

  val queryActors = new HashMap[DatasetRef, ActorRef]
  val nodeCoordinatorActorsMap = new HashMap[Address, ActorRef]
  val nodeCoordinatorActors = new ArrayBuffer[ActorRef]
  val random = new Random()


  // For now, datasets need to be set up for ingestion before they can be queried (in-mem only)
  // TODO: if we ever support query API against cold (not in memory) datasets, change this
  def withQueryActor(originator: ActorRef, dataset: DatasetRef)(func: ActorRef => Unit): Unit = {
    logger.info(s"finding query actor for dataset: $dataset from queryActors: $queryActors")
    queryActors.get(dataset) match {
      case Some(queryActor) => func(queryActor)
      case None => func(getRandomActor(nodeCoordinatorActors, random))
    }
  }

  private def getRandomActor(list: ArrayBuffer[ActorRef], random: Random): ActorRef = {
    val randomActor: ActorRef = list(random.nextInt(list.size))
    logger.info(s"returning random: $randomActor")
    return randomActor
  }

  def receiveMemberEvent(memberEvent: MemberEvent, context: ActorContext, coord: ActorRef)
                        (implicit execContext: ExecutionContext): Unit = {
    memberEvent match {
      case MemberUp(member) =>
        logger.debug(s"Member up received: $member")
        val memberCoordActorPath = nodeCoordinatorPath(member.address)
        context.actorSelection(memberCoordActorPath).resolveOne(settings.ResolveActorTimeout)
          .map(ref => coord ! NodeCoordinatorActorDiscovered(memberCoordActorPath.address, ref))
          .recover {
            case e: Exception =>
              logger.warn(s"Unable to resolve coordinator at $memberCoordActorPath, ignoring. ", e)
          }

      case MemberRemoved(member, _) => {
        val memberCoordActorPath = nodeCoordinatorPath(member.address)
        nodeCoordinatorActorsMap.get(memberCoordActorPath.address) match {
          case Some(x) => {
            nodeCoordinatorActorsMap.remove(memberCoordActorPath.address)
            nodeCoordinatorActors -= x
          }
          case None => logger.warn(s"Member not in coordinatorsMap: $memberCoordActorPath.address")
        }
        logger.debug(s"Member down updated map: $nodeCoordinatorActorsMap")
        logger.debug(s"Member down updated list: $nodeCoordinatorActors")
      }

      case _: MemberEvent => // ignore
    }
  }

  def receiveNodeCoordDiscoveryEvent(ncaDiscoveredEvent: NodeCoordinatorActorDiscovered)
                                    (implicit executionContext: ExecutionContext): Unit = {
    ncaDiscoveredEvent match {
      case NodeCoordinatorActorDiscovered(addr, coordRef) => {
        logger.debug(s"Received NodeCoordDiscoveryEvent $addr  $coordRef")
        nodeCoordinatorActorsMap(addr) = coordRef
        nodeCoordinatorActors += coordRef
        logger.debug(s"Member up updated map: $nodeCoordinatorActorsMap")
        logger.debug(s"Member up updated list: $nodeCoordinatorActors")
      }
    }
  }

}