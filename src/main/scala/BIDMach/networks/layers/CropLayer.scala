package BIDMach.networks.layers

import BIDMat.{Mat,SBMat,CMat,DMat,FMat,IMat,LMat,HMat,GMat,GDMat,GIMat,GLMat,GSMat,GSDMat,SMat,SDMat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import BIDMach.datasources._
import BIDMach.updaters._
import BIDMach.mixins._
import BIDMach.models._
import BIDMach._
import edu.berkeley.bid.CPUMACH
import edu.berkeley.bid.CUMACH
import scala.util.hashing.MurmurHash3;
import java.util.HashMap;
import BIDMach.networks._


/**
 * Crop layer. Crops the input according to the dimensions specified in the "sizes" option. 
 * A zero size leaves that dimension untouched. 
 * Does not pass derivatives
 */

class CropLayer(override val net:Net, override val opts:CropNodeOpts = new CropNode) extends Layer(net, opts) {
  var blockInds:Array[IMat] = null;
  
  def setupInds = {
    val dims = inputData.dims;
    if (dims.length != opts.sizes.length) throw new RuntimeException("CropLayer sizes param doesnt match input dimension");
    blockInds = new Array[IMat](dims.length);
    for (i <- 0 until dims.length) {
      blockInds(i) = if (opts.sizes(i) <= 0 || dims(i) - opts.sizes(i) <= 0) {
        ? 
      } else {
        val gap = dims(i) - opts.sizes(i);
        val offset = if (opts.offsets.asInstanceOf[AnyRef] != null && opts.offsets(i) >= 0) {
          math.min(opts.offsets(i), gap);
        } else {
          gap/2;
        }
        irow(offset->(offset + opts.sizes(i)))
      } 
    }
  }

	override def forward = {
			val start = toc;
			if (blockInds.asInstanceOf[AnyRef] == null) setupInds;
			
			blockInds.length match {
			  case 2 => output = inputData(blockInds(0), blockInds(1));
			  case 3 => output = inputData(blockInds(0), blockInds(1), blockInds(2));
			  case 4 => output = inputData(blockInds(0), blockInds(1), blockInds(2), blockInds(3));
			  case 5 => output = inputData(blockInds(0), blockInds(1), blockInds(2), blockInds(3), blockInds(4));
			}

			forwardtime += toc - start;
	}
  
  override def toString = {
    "crop@"+Integer.toHexString(hashCode % 0x10000).toString
  }
}

trait CropNodeOpts extends NodeOpts {
  var sizes:IMat = irow(3, 224, 224, 0)
  var offsets:IMat = irow(0, -1, -1, -1)
  def copyOpts(opts:CropNodeOpts):CropNodeOpts = {
  		super.copyOpts(opts);
  		opts.sizes = sizes;
  		opts.offsets = offsets;
  		opts;
  }
}

class CropNode extends Node with CropNodeOpts {
  def copyTo(opts:CropNode):CropNode = {
    this.asInstanceOf[Node].copyTo(opts);
    copyOpts(opts);
    opts
  }

	override def clone:CropNode = {copyTo(new CropNode).asInstanceOf[CropNode];}

  override def create(net:Net):CropLayer = {CropLayer(net, this);}
  
  override def toString = {
    "crop@"+Integer.toHexString(hashCode % 0x10000).toString
  }
}

object CropLayer {  
  
  def apply(net:Net) = new CropLayer(net, new CropNode);
  
  def apply(net:Net, opts:CropNode) = new CropLayer(net, opts);
}