/*
 * Authors: Anurag Migliani and Thomas Zamojski
 * Date: 12/22/15.
 * Project: Parallelized Systems for Master Big Data at Ensai.
 * Run: using sbt, run from the top directory.
 *
 * Implementation of basic paxos, aka the synod algorithm.
 * From here, multi-paxos is not very different, but is not implemented. Only a single value will be chosen. Multi-paxos semms to require a bit more knowledge of akka that we managed to get into.
 *
 * Actors:
 *   - Node   : represents a physical node on the distributed system. It will play all the roles of the algorithm: leader, proposer, acceptor, learner.
 *   - Leader : no implementation of elections here. When a client sends a request to a node, that one becomes leader. All it does is initiate a child Proposal actor.
 *   - Proposal : A proposal being proposed. It acts like a proposer in paxos made simple by Lamport.
 *   - Accepter : as in paxos made simple by Lamport.
 *
 * Messages:
 *  - Request: the Client program sends a ask request through ClientRequest
 *  - Phase 1: Proposer sends a PrepareMsg with proposal number.
 *          Acceptors can send back their lastVote through PromiseMsg.
 *  - Phase 2: If a quorum has sent back lastVotes, the Proposer can take the value of the highest vote and send a ProposeMsg with the decree.
 *          Acceptors can send back an AcceptMsg if they agree with the proposition.
 *  - Learning: Upon receiving accepts from a quorum, the proposer sends a LearnedMsg with the decree to all nodes and the client.
 *
 * Ledgers: There is information stored into persistent memory in case of failures. We implemented it by hand, meaning with reads/writes to files.
 *  - nextPropNum: a Node needs to remember where it is at in the proposal numbers to avoid initiating several proposals with the same number.
 *  - lastVote: an acceptor needs to remember its last vote.
 *  - promise Num: an acceptor's highest proposal number it has promised to.
 *  - decree Value: when a value is chosen, each Node can memorise it too.
 */

package paxosbyanurag

import akka.actor.{Actor, ActorRef, Props}
import scala.collection.mutable
import java.io.{FileWriter, BufferedWriter, File}
import java.nio.file.{Paths, Files}
import scala.math.Ordered.orderingToOrdered


case class ProposalNum(node: String, proposal: Int) extends Ordered[ProposalNum] {
  def compare(that: ProposalNum) = (this.proposal, this.node) compare (that.proposal, that.node)
}

case class Decree(num: Int, value: String)

// Votes: two types, either empty or casted.
abstract class Vote(val node: String)
case class CastedVote(num: ProposalNum, override val node: String, decree: Decree) extends Vote(node)
case class EmptyVote(override val node: String) extends Vote(node)

// Messages: case classes are immutable and can be matched by pattern.
// First four from Paxos made simple: the heart of the algorithm
case class PrepareMsg(num: ProposalNum)
case class PromiseMsg(num: ProposalNum, vote: Vote)
case class ProposeMsg(num: ProposalNum, decree: Decree)
case class AcceptMsg(num: ProposalNum, node: String)
// Next three messages for optimisation: if an acceptor has promised a higher proposal, tells the proposer with HavePromisedMsg, who enters with its parent node to get a new number.
// Any of these messages can be lost because a proposal can safely be dropped anyways.
case class HavePromisedMsg(num: ProposalNum)
case class IncPropMsg(num: ProposalNum) // Proposer asks node to increase number
case class NewPropNumMsg(num: ProposalNum) // Node returns a new number
// For clients and learning.
case class LearnedMsg(decree: Decree)
case class ClientRequest(value: String)

// Node: quorumSize is the size of a majority set.
// As leader: check in memory for decree. If NA, initiate a proposal.
// As acceptor: as in paxos simple + simple optimisation.
class Node(quorumSize: Int) extends Actor {

  var myName: String = self.path.name
  var nextPropInt: Int = -1
  var nextProp: Option[ProposalNum] = None

  def receive = leader orElse acceptor

  def leader: Receive = {
    case ClientRequest(value) =>
      val s = sender
      println("Client's request to " + myName + " for value " + value)

      // First checks into the ledger if the value has been chosen
      var z = new Array[String](0)
      if(Files.exists(Paths.get("var/" + myName + "decrees"))) {
        for (line <- scala.io.Source.fromFile("var/" + myName + "decrees").getLines()) {
          z = line.split(":")
        }
      }
      if (!z.isEmpty) {
        println("Value already chosen, returning it")
        s ! LearnedMsg(new Decree(z(0).toInt,z(1)))
      }
      else {
        // nextPropInt is memorised
        nextPropInt += 1
        val file = new File("var/" + myName + "-nextPropInt")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(nextPropInt.toString)
        bw.close()

        // Initiating a proposal to the synod.
        val propNum = ProposalNum(myName, nextPropInt)
        context.actorOf(Props(new Proposal(propNum, 3, Decree(0, value), s)))
      }

    case IncPropMsg(num) =>
      // Increase the proposal number, memorise it and update the proposal.
      nextPropInt = num.proposal + 1
      val file = new File("var/" + myName + "-nextPropInt")
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write(nextPropInt.toString)
      bw.close()
      val propNum = ProposalNum(myName, nextPropInt)
      sender ! NewPropNumMsg(propNum)
  }

  def acceptor: Receive = {

    case PrepareMsg(propnum) =>
      println(myName + " received prepare proposal (" + propnum.node + ", " + propnum.proposal.toString + ")")

      // Reading nextProp from the ledger.
      if(Files.exists(Paths.get("var/" + myName + "promise"))) {
        for (line <- scala.io.Source.fromFile("var/" + myName + "promise").getLines()) {
          var z: Array[String] = line.split(":")
          nextProp = Some(new ProposalNum(z(0), z(1).toInt))
        }
      }

      // Optimisation: if have promised already, send a message to proposer.
      if (!nextProp.isEmpty && propnum < nextProp.get){
        println(myName + " has promised not to vote on proposals lower than (" + nextProp.get.node + ", " + nextProp.get.proposal.toString + ")")
        sender ! HavePromisedMsg(nextProp.get)
      }

      // Otherwise, we can promise and send last vote
      if (nextProp.isEmpty || propnum > nextProp.get) {
        nextProp = Some(propnum)
        // Persist nextProp by writing to ledger
        val file = new File("var/" + myName + "promise")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(propnum.node + ":" + propnum.proposal.toString)
        bw.close()

        // Read the lastVote from the ledger
        var vot: Vote = null
        var z = new Array[String](0)
        if(Files.exists(Paths.get("var/" + myName + "lastvote"))) {
          for (line <- scala.io.Source.fromFile("var/" + myName + "lastvote").getLines()) {
            z = line.split(":")
          }
        }
        if (!z.isEmpty) {
          vot = new CastedVote(new ProposalNum(z(0), z(1).toInt), z(2), new Decree(z(3).toInt, z(4)))
          println(myName + " is sending promise with last vote with number (" + z(0) + ", " + z(1) + ") and decree (" + z(3) + ", " + z(4) + ")")
        }
        else {
          vot = new EmptyVote(myName)
          println(myName + " is sending promise with an empty last vote")
        }

        sender ! PromiseMsg(propnum, vot)
      }

    case ProposeMsg(n, d) =>
      println(myName + " received proposal numbered (" + n.node + ", " + n.proposal.toString + ") with decree (" + d.num.toString + ", " + d.value + ")")

      // Reading nextProp from memory
      for (line <- scala.io.Source.fromFile("var/" + myName + "promise").getLines()) {
        var z: Array[String] = line.split(":")
        nextProp = Some(new ProposalNum(z(0), z(1).toInt))
      }

      // If promised to higher number, tells the proposer
      if (nextProp > Some(n)) {
        println(myName + " has promised not to vote on proposals lower than (" + nextProp.get.node + ", " + nextProp.get.proposal.toString + ")")
        sender ! HavePromisedMsg(nextProp.get)
      }
      else {
        // If didn't promised to higher number, remember this vote and send acceptMsg
        val file = new File("var/" + myName + "lastvote")
        val bw = new BufferedWriter(new FileWriter(file))
        bw.write(n.node + ":" + n.proposal.toString + ":" + myName + ":" + d.num.toString + ":" + d.value)
        bw.close()

        sender ! AcceptMsg(n, myName)
      }


    case LearnedMsg(d) =>
      println(myName + " received a proposal success message for decree (" + d.num.toString + ", " + d.value + ")")
      // Memorise the decree
      val file = new File("var/" + myName + "decrees")
      val bw = new BufferedWriter(new FileWriter(file))
      bw.write(d.num.toString + ":" + d.value)
      bw.close()

    case _ =>

  }
}

class Proposal(var propNum: ProposalNum, quorumSize: Int, var d: Decree, listener: ActorRef) extends Actor {
  //val log = Logging(context.system,this)
  val votes: mutable.MutableList[CastedVote] = mutable.MutableList()
  var quorum: Set[String] = Set[String]()

  context.actorSelection("../../*") ! PrepareMsg(propNum)

  def receive: Receive = {

    case HavePromisedMsg(num) =>
      println("Proposal (" + propNum.node + ", " + propNum.proposal.toString + ") is too low: increasing its number")
      context.parent ! IncPropMsg(num)

    case NewPropNumMsg(num) =>
      propNum = num
      context.actorSelection("../../*") ! PrepareMsg(propNum)

    case PromiseMsg(n,v) =>
      val q: String = v.node
      if (!quorum.contains(q) && quorum.size != quorumSize) {
        quorum = quorum + q
        v match {
          case x: CastedVote =>
            votes += x
          case _ =>
        }

        if (quorum.size == quorumSize) {
          if (!votes.isEmpty) {
            d = votes.maxBy{ v => v.num }.decree
          }
          println("Proposal number (" + n.node + ", " + n.proposal.toString + ") with decree (" + d.num.toString + ", " + d.value + ") ready to begin")
          quorum.map(q => context.actorSelection("../../" + q)).foreach(a => a ! ProposeMsg(n, d))
        }
      }

    case AcceptMsg(n, q) =>
      println("Received vote from node " + q + " for proposal number (" + n.node + ", " + n.proposal.toString + ")")
      if (quorum.size > 0) {
        quorum -= q
        if (quorum.size == 0) {
          println("Proposal number (" + n.node + ", " + n.proposal.toString + ") is successful: decree chosen is (" + d.num.toString + ", " + d.value + ")")
          listener ! LearnedMsg(d)
          context.actorSelection("../../*") ! LearnedMsg(d)
        }
      }

    case _ =>

  }
}