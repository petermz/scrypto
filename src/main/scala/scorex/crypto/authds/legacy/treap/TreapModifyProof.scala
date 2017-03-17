package scorex.crypto.authds.legacy.treap

import scorex.crypto.authds.TwoPartyDictionary.Label
import scorex.crypto.authds._
import scorex.crypto.authds.avltree.batch.{Lookup, Modification, Operation}
import scorex.crypto.authds.legacy.treap.Constants.{LevelFunction, TreapKey, TreapValue}
import scorex.crypto.hash.ThreadUnsafeHash
import scorex.utils.ByteArray

import scala.util.{Failure, Success, Try}

case class TreapModifyProof(key: TreapKey, proofSeq: Seq[WTProofElement])
                           (implicit hf: ThreadUnsafeHash, levelFunc: LevelFunction)
  extends TwoPartyProof[TreapKey, TreapValue] {

  def verify[O <: Operation](digest: Label, operation: O): Option[Label] = Try {
    initializeIterator()

    // returns the new flat root
    // and an indicator whether tree has been modified at r or below
    // Also returns the label of the old root
    def verifyHelper(): (VerifierNodes, Boolean, Label) = {
      dequeueDirection() match {
        case LeafFound =>
          val nextLeafKey: TreapKey = dequeueNextLeafKey()
          val value: TreapValue = dequeueValue()
          operation match {
            case m: Modification =>
              m.updateFn(Some(value)) match {
                case Success(None) => //delete value
                  ???
                case Success(Some(v)) => //update value
                  val oldLeaf = Leaf(key, value, nextLeafKey)
                  val newLeaf = Leaf(key, v, nextLeafKey)
                  (newLeaf, true, oldLeaf.label)
                case Failure(e) => // found incorrect value
                  throw e
              }
            case l: Lookup => ??? //todo: finish
          }
        case LeafNotFound =>
          val neighbourLeafKey = dequeueKey()
          val nextLeafKey: TreapKey = dequeueNextLeafKey()
          val value: TreapValue = dequeueValue()
          require(ByteArray.compare(neighbourLeafKey, key) < 0)
          require(ByteArray.compare(key, nextLeafKey) < 0)

          val r = new Leaf(neighbourLeafKey, value, nextLeafKey)
          val oldLabel = r.label
          operation match {
            case m: Modification =>
              m.updateFn(None) match {
                case Success(None) => //don't change anything, just lookup
                  ???
                case Success(Some(v)) => //insert new value
                  val newLeaf = new Leaf(key, v, r.nextLeafKey)
                  r.nextLeafKey = key
                  val level = levelFunc(key)
                  val newR = VerifierNode(r.label, newLeaf.label, level)
                  (newR, true, oldLabel)
                case Failure(e) => // found incorrect value
                  // (r, false, false, oldLabel)
                  throw e
              }
            case l: Lookup => ??? //todo: finish
          }
        case GoingLeft =>
          val rightLabel: Label = dequeueRightLabel()
          val level: Level = dequeueLevel()

          val (newLeftM, changeHappened, oldLeftLabel) = verifyHelper()

          val r = VerifierNode(oldLeftLabel, rightLabel, level)
          val oldLabel = r.label

          if (changeHappened) {
            newLeftM match {
              case newLeft: VerifierNode if newLeft.level >= r.level =>
                // We need to rotate r with newLeft
                r.leftLabel = newLeft.rightLabel
                newLeft.rightLabel = r.label
                (newLeft, true, oldLabel)
              case newLeft =>
                // Attach the newLeft because its level is smaller than our level
                r.leftLabel = newLeft.label
                (r, true, oldLabel)
            }
          } else {
            (r, false, oldLabel)
          }
        case GoingRight =>
          val leftLabel: Label = dequeueLeftLabel()
          val level: Level = dequeueLevel()

          val (newRightM, changeHappened, oldRightLabel) = verifyHelper()

          val r = VerifierNode(leftLabel, oldRightLabel, level)
          val oldLabel = r.label

          if (changeHappened) {
            // This is symmetric to the left case, except of >= replaced with > in the
            // level comparison
            newRightM match {
              case newRight: VerifierNode if newRight.level > r.level =>
                // We need to rotate r with newRight
                r.rightLabel = newRight.leftLabel
                newRight.leftLabel = r.label
                (newRight, true, oldLabel)
              case newRight =>
                // Attach the newRight because its level is smaller than or equal to our level
                r.rightLabel = newRight.label
                (r, true, oldLabel)
            }
          } else {
            // no change happened
            (r, false, oldLabel)
          }
      }
    }

    val (newTopNode, changeHappened, oldLabel) = verifyHelper()
    if (oldLabel sameElements digest) {
      Some(newTopNode.label)
    } else {
      None
    }
  }.getOrElse(None)

}
