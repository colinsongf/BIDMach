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
import jcuda.jcudnn._
import jcuda.jcudnn.JCudnn._
import scala.util.hashing.MurmurHash3;
import java.util.HashMap;
import BIDMach.networks._

/**
 * Basic Net Layer class. There are currently 17 layer types:
 - InputLayer: just a placeholder for the first layer which is loaded with input output blocks. No learnable params. 
 - LinLayer: Linear layer. Has a matrix of learnable params which is the input-output map. 
 - RectLayer: Rectifying one-to-one layer. No params.
 - GLMLayer: a one-to-one layer with GLM mappings (linear, logistic, abs-logistic and SVM). No learnable params. 
 - NormLayer: normalizing layer that adds a derivative term based on the difference between current layer norm and a target norm. 
   No learnable params. The target norm and weight of this derivative term can be specified. 
 - DropoutLayer: A layer that implements random dropout. No learnable params, but dropout fraction can be specified. 
 - AddLayer: adds input layers element-wise.
 - MulLayer: multiplies input layers element-wise. Will also perform edge operations (one input can be a scalar). 
 - SoftmaxLayer: a softmax (normalized exponential) layer.
 - TanhLayer: Hyperbolic tangent non-linearity.
 - SigmoidLayer: Logistic function non-linearity.
 - SoftplusLayer: smooth ReLU unit. 
 - LnLayer: natural logarithm
 - ExpLayer: exponential
 - SumLayer: column-wise sum
 - CopyLayer: copies its input to its output. 
 - OnehotLayer: Converts an integer array of feature values to a sparse matrix whose columns are the instances with one non-zero in the feature position. 
 *
 *
 *
 * Currently only four Layer types need params:
 - LinLayer: "outside" holds the output dimensions of the FClayer (input dimension set by previous layer). 
 - GLMLayer: "links" holds the links matrix (integer optss for loss types, see GLM), for the output of that layer. Its size should match the number of targets.
 - NormLayer: "targetNorm" holds a target per-element norm, and "weight" is the weight for this term in the derivative calculation.
 - DropoutLayer: "frac" holds the fraction of neurons to retain.
 *
 * The network topology is normally specified by opts.layers which is a sequence of "Layer.Options" objects. There is a nested Options
 * Class for each Layer class, which holds the params for defining that layer, and pointers to any input Layers via their Options classes.
 * In other words, the options classes allow construction of a mirror of the actual network topology. This allows patterns of
 * structure to be repeated using a single Options graph structure. 
 * 
 * Each NodeSet instance has up to two inputs which are other NodeSet instances (or null). This graph structure can be cyclic. 
 * When the model is created, the Layer structure mimics the NodeSet structure. 
 * 
 * You can also create the Layer graph directly using the "setinput()" method in each layer. 
 */

// Notes: 
// Layer Nodes can have multiple inputs and multiple outputs. 
// Each layer contains an array of inputs, an array of outputs, and an array of derivatives. 
// The output and derivatives are "owned" by the node and are simple arrays of Mat. 
//
// The inputs comprise a reference to another layer and an integer which is the number of output of that layer to use. 
// _inputs(i): refers to input layer i, and _inputNums(i): the number of the output of layer i we are using. 
//
// To simplify references to input matrices, convenience functions are provided:
//   inputData: refers to this layers first input matrix. 
//   inputDeriv: refers to the derivative matrix for the first input. 
//   inputDatas(i): refers to the i^th input matrix of this layer.
//   inputDerivs(i); refers to the derivative of the i^th input layer. 
//
// its also possible to assign to inputDeriv for backward processing. 
//
// To set layer A's i^th input to layer B's default (0th) output, do A.setinput(i, B)
// To set layer A's i^th input to layer B's j^th output, do A.setinout(i, B, j)

@SerialVersionUID(100L)
class Layer(val net:Net, val opts:NodeOpts = new Node) extends LayerTerm(null, 0) {
  // Internal data arrays
  val _inputs = new Array[LayerTerm](1);
  val _outputs = new Array[Mat](1);
  val _derivs = new Array[Mat](1);
  def inputlength = _inputs.length
  var forwardtime = 0.0
  var backwardtime = 0.0
  override def layer = this
  def inputs = _inputs;
  
  private var _GUID = Mat.myrand.nextLong
  def setGUID(v:Long):Unit = {_GUID = v}
  def GUID:Long = _GUID
  
  Net.addLayer(this);
  
  // Setters and getters for general elements of those arrays
  def outputs(i:Int) = _outputs(i);
  def derivs(i:Int) = _derivs(i);  
  def input(i:Int) = _inputs(i);
  def apply(i:Int) = new LayerTerm(this, i);
  
  def setOutput(i:Int, v:Mat):Layer = {_outputs(i) = v; this}
  def setDeriv(i:Int, v:Mat):Layer = {_derivs(i) = v; this}
  def setInput(i:Int, v:LayerTerm) = {_inputs(i) = v; this}
  def setInputs(v0:LayerTerm, v1:LayerTerm) = {setInput(0, v0); setInput(1, v1); this}
  def setInputs(v0:LayerTerm, v1:LayerTerm, v2:LayerTerm) = {setInput(0, v0); setInput(1, v1); setInput(2, v2); this}
  
  // Setters and getters for the first input or output
  def input = _inputs(0);
  def output = _outputs(0);
  def deriv = _derivs(0);
  
  def input_=(v:LayerTerm): Unit = {_inputs(0) = v}
  def output_= (v:Mat):Unit = {_outputs(0) = v};
  def deriv_=(v:Mat):Unit = {_derivs(0) = v};
  
  // Input getters (and one setter) which get the appropriate output from each input layer
  def inputData = {val i = _inputs(0); i.layer._outputs(i.term);}
  def inputDeriv = {val i = _inputs(0); i.layer._derivs(i.term);}
  def inputDeriv_=(v:Mat):Unit = {val i = _inputs(0); i.layer._derivs(i.term) = v;}  
  def inputDatas(i:Int) = {val lt = _inputs(i); lt.layer._outputs(lt.term);}
  def inputDerivs(i:Int) = {val lt = _inputs(i); lt.layer._derivs(lt.term);}
  
  var target:Mat = null;
  def forward = {};
  def backward:Unit = {};
  def backward(ipass:Int, pos:Long):Unit = backward;
  def score:FMat = zeros(1,1);
  var parent:Layer = null;
  lazy val modelmats = net.modelmats;
  lazy val updatemats = net.updatemats;
  lazy val useGPU = net.useGPU;
  lazy val nopts = net.opts;
  def convertMat(mat:Mat) = {net.convertMat(mat);}

  def createOutput = {
  	if (output.asInstanceOf[AnyRef] == null) output = inputData.zeros(inputData.dims);
  }

  def createOutput(dims:IMat) = {
  	if (output.asInstanceOf[AnyRef] == null) output = inputData.zeros(dims);
  }

  def clearDeriv = {
  	if (deriv.asInstanceOf[AnyRef] == null) deriv = output.zeros(output.dims);
  	deriv.clear;
  }
  
  def clearDerivLazy = {
  	if (deriv.asInstanceOf[AnyRef] == null && inputDeriv.asInstanceOf[AnyRef] != null) deriv = output.zeros(output.dims);
  	deriv.clear;
  }
  
  def clearDerivs = {
    if (deriv.asInstanceOf[AnyRef] == null) {
      for (i <- 0 until _outputs.length) {
        _derivs(i) = output.zeros(_outputs(i).dims);
      }
    }
    for (i <- 0 until _derivs.length) {
      _derivs(i).clear
    }
  }
  
  def getModelMats(net:Net):Unit = {}
  
  override def toString = {
    "layer@"+(hashCode % 0x10000).toString
  }
}

class LayerTerm(val _layer:Layer, val term:Int) extends Serializable {
  def layer = _layer;
  
  def +    (a:LayerTerm) = {val n=this; new AddLayer(null){inputs(0)=n; inputs(1)=a}};
  
  def -    (a:LayerTerm) = {val n=this; new SubLayer(null){inputs(0)=n; inputs(1)=a}};

  def *@   (a:LayerTerm) = {val n=this; new MulLayer(null){inputs(0)=n; inputs(1)=a;}};
    
  def ∘    (a:LayerTerm) = {val n=this; new MulLayer(null){inputs(0)=n; inputs(1)=a;}};
  
  def dot  (a:LayerTerm) = {val n=this; new DotLayer(null){inputs(0)=n; inputs(1)=a;}};
  
  def ∙    (a:LayerTerm) = {val n=this; new DotLayer(null){inputs(0)=n; inputs(1)=a;}};
        
  def over (a:LayerTerm) = {val n=this; new StackLayer(null){inputs(0)=n; inputs(1)=a;}};
  
  def apply(a:LayerTerm) = {val n=this; new SelectLayer(null){inputs(0)=n; inputs(1)=a;}};
}

object Layer {
  
  def findNet(net:Net):Net = {
    if (net.asInstanceOf[AnyRef] != null) {
      net;
    } else {
      Net.defaultNet;
    }
  }
  
  def batchNorm(a:LayerTerm)(avgFactor:Float=0.1f, normMode:Int=BatchNormLayer.SPATIAL) = {
    new BatchNormLayer(null, new BatchNormNode{expAvgFactor=avgFactor; batchNormMode=normMode}){inputs(0)=a;}
  }
  
  def batchNormScale(a:LayerTerm)(net:Net=null, name:String="", avgFactor:Float=0.1f, normMode:Int=BatchNormLayer.SPATIAL, hasBias:Boolean = true) = {
  	val net0 = findNet(net);
    val hb = hasBias;
  	val mname = name;
    new BatchNormScaleLayer(net0, new BatchNormScaleNode{modelName = mname; expAvgFactor=avgFactor; batchNormMode=normMode; hasBias=hb}){inputs(0)=a;}
  }
  
  def constant(v:Mat)(net:Net=null):ConstantLayer = {
  	val net0 = findNet(net);
    new ConstantLayer(net0, new ConstantNode{value = v;})
  }
  
  def const(v:Mat):ConstantLayer = {
  	val net0 = findNet(null);
    new ConstantLayer(net0, new ConstantNode{value = v;})
  }
  
  def conv(a:LayerTerm)(net:Net=null, name:String="", w:Int, h:Int, nch:Int, initv:Float = 1f, stride:IMat = irow(1), pad:IMat = irow(1), 
      hasBias:Boolean = true, convType:Int=cudnnConvolutionMode.CUDNN_CROSS_CORRELATION) = {
  	val net0 = findNet(net);
    val str = stride;
    val pd = pad;
    val hb = hasBias;
    val mname = name;
    val initv0 = initv;
    val ct = convType;
    new ConvLayer(net0, new ConvNode{modelName = mname; kernel=irow(w,h); noutputs=nch; initv=initv0; stride=str; pad=pd; hasBias=hb; convType=ct}){inputs(0)=a;};
  }
  
  def copy(a:LayerTerm) = new CopyLayer(null){inputs(0) = a;}

  def copy = new CopyLayer(null);
  
  def crop(a:LayerTerm)(sizes:IMat=irow(3,224,224,0), offsets:IMat=irow(0,-1,-1,-1)) = {
    val csizes = sizes;
    val coffsets = offsets;
    new CropLayer(null, new CropNode{sizes = csizes; offsets = coffsets}){inputs(0) = a;}
  }
  
  def dropout(a:LayerTerm)(dfrac:Float) = new DropoutLayer(null, new DropoutNode{frac = dfrac}){inputs(0) = a}
  
  def efn(a:LayerTerm)(fwdfn:(Float)=>Float=null, bwdfn:(Float,Float,Float)=>Float=null) = {
    val fwd = fwdfn;
    val bwd = bwdfn;
    new EfnLayer(null, new EfnNode{fwdfn=fwd; bwdfn=bwd}){inputs(0) = a;};
  }
  
  def exp(a:LayerTerm) = new ExpLayer(null){inputs(0) = a;};
  
  def fn(a:LayerTerm)(fwdfn:(Mat)=>Mat=null, bwdfn:(Mat,Mat,Mat)=>Mat=null) = {
    val fwd = fwdfn;
    val bwd = bwdfn;
    new FnLayer(null, new FnNode{fwdfn=fwd; bwdfn=bwd}){inputs(0) = a;};
  }
   
  def format(a:LayerTerm)(net:Net = null, conversion:Int = TensorFormatLayer.AUTO, inputFormat:Int = Net.TensorNHWC) = {
  	val net0 = findNet(net);
    val con = conversion;
    val fmt = inputFormat;
    new TensorFormatLayer(net0, new TensorFormatNode{conversion = con; inputFormat = fmt;}){inputs(0) = a;}
  }
  
  def forward(a:LayerTerm) = new ForwardLayer(null){inputs(0) = a;}
  
  def GLM(a:LayerTerm)(implicit opts:GLMNodeOpts) = new GLMLayer(null, opts){inputs(0) = a};
  
  def input(a:LayerTerm) = new InputLayer(null){inputs(0) = a;};
  
  def input = new InputLayer(null);
  
  def linear(a:LayerTerm)(net:Net = null, name:String="", outdim:Int=0, hasBias:Boolean=true, initv:Float = 1f, aopts:ADAGrad.Opts=null, 
      withInteractions:Boolean=false, tmatShape:(Int,Int)=>(Array[Int], Array[Int], Array[Int], Array[Int]) = null) = {
  	val net0 = findNet(net);
    val odim = outdim;
    val hBias = hasBias;
    val aaopts = aopts;
    val mname = name;
    val tms = tmatShape;
    val wi = withInteractions;
    val initv0 = initv;
    new LinLayer(net0, new LinNode{modelName = mname; outdim=odim; hasBias=hBias; initv=initv0; aopts=aaopts; withInteractions=wi; tmatShape = tms}){inputs(0)=a;};
  }
  
  def ln(a:LayerTerm) = new LnLayer(null){inputs(0) = a};
  
  def lstm(h:LayerTerm, c:LayerTerm, i:LayerTerm, m:String)(net:Net=null, opts:LSTMNodeOpts) = {
  	val net0 = findNet(net);
    val node = new LSTMNode;
    opts.copyOpts(node);
    node.modelName = m;
    node.constructGraph;
    val n = new LSTMLayer(net0, node);
    n.setInput(0, h);
    n.setInput(1, c);
    n.setInput(2, i);
    n
  }

  
  def negsamp(a:LayerTerm)(net:Net=null, name:String="", outdim:Int=0, hasBias:Boolean=true, aopts:ADAGrad.Opts=null, nsamps:Int=100, expt:Float=0.5f, scoreType:Int=0, doCorrect:Boolean=true) = {
  	val net0 = findNet(net);
    val odim = outdim;
    val hBias = hasBias;
    val aaopts = aopts;
    val nnsamps = nsamps;
    val eexpt = expt;
    val dcr = doCorrect;
    val sct = scoreType;
    val mname = name;
    new NegsampOutputLayer(net0, new NegsampOutputNode{modelName=mname; outdim=odim; hasBias=hBias; aopts=aaopts; nsamps=nnsamps; expt=eexpt; scoreType=sct; docorrect=dcr}){inputs(0)=a;};
  }
  
  def norm(a:LayerTerm)(opts:NormNodeOpts) = new NormLayer(null){inputs(0) = a;}
  
  def oneHot(a:LayerTerm) = new OnehotLayer(null){inputs(0) = a};
  
  def pool(a:LayerTerm)(net:Net=null, h:Int=1, w:Int=1, stride:Int=1, pad:Int=0, 
      poolingMode:Int=cudnnPoolingMode.CUDNN_POOLING_MAX, 
      poolingNaN:Int=cudnnNanPropagation.CUDNN_PROPAGATE_NAN,
      tensorFormat:Int = Net.UseNetFormat) = {
  	val net0 = findNet(net);
  	val hh = h;
  	val ww = w;
  	val str = stride;
  	val ppad = pad;
  	val pm = poolingMode;
  	val pn = poolingNaN;
  	val tf = tensorFormat;
    new PoolingLayer(net0, new PoolingNode{h=hh; w=ww; stride=str; pad=ppad; poolingMode=pm; poolingNaN=pn; tensorFormat=tf;}){inputs(0)=a;}  
  }
  
  def scale(a:LayerTerm)(net:Net=null, name:String="", normMode:Int=BatchNormLayer.SPATIAL, hasBias:Boolean = true) = {
  	val net0 = findNet(net);
  	val hb = hasBias;
  	val mname = name;
    new ScaleLayer(net0, new ScaleNode{modelName = mname; batchNormMode=normMode; hasBias=hb}){inputs(0)=a;}   
  }
    
  def rect(a:LayerTerm) = new RectLayer(null){inputs(0) = a};
  
  def relu(a:LayerTerm) = new RectLayer(null){inputs(0) = a};
  
  def sigmoid(a:LayerTerm) = new SigmoidLayer(null){inputs(0) = a};
  
  def σ(a:LayerTerm) = new SigmoidLayer(null){inputs(0) = a};

  def softmax(a:LayerTerm) = new SoftmaxLayer(null){inputs(0) = a};
  
  def softmaxout(a:LayerTerm)(scoreTyp:Int=0, doVar:Boolean=false) =  new SoftmaxOutputLayer(null, new SoftmaxOutputNode{scoreType=scoreTyp;doVariance=doVar}){inputs(0) = a}
  
  def softplus(a:LayerTerm) = new SoftplusLayer(null){inputs(0) = a};
  
  def splithoriz(a:LayerTerm)(np:Int) = new SplitHorizLayer(null, new SplitHorizNode{nparts = np}){inputs(0) = a};
  
  def splitvert(a:LayerTerm)(np:Int) = new SplitVertLayer(null, new SplitVertNode{nparts = np}){inputs(0) = a};
  
  def sum(a:LayerTerm) = new SumLayer(null){inputs(0) = a};
  
  def tanh(a:LayerTerm) = new TanhLayer(null){inputs(0) = a};
  
}


trait OutputLayer {}

object LayerFn {
  final val SIGMOIDFN = 0;
  final val TANHFN = 1;
  final val SOFTPLUSFN = 2;
  
  val fwdflops = irow(20, 20, 40);
  val bwdflops = irow(3, 3, 20);
  
  // Loosely check dimensions. Skip dimensions of 1 in either tensor.
  def checkdims(dims0:IMat, dims1:IMat) = {
    if (dims1.asInstanceOf[AnyRef] != null) {
      var i0 = 0;
      var i1 = 0;
      while (i0 < dims0.length && i1 < dims1.length) {
        while (i0 < dims0.length && dims0(i0) == 1) i0 += 1;
        while (i1 < dims1.length && dims1(i1) == 1) i1 += 1; 
        if ((i0 >= dims0.length) != (i1 >= dims1.length)) {
          throw new RuntimeException("dimensions mismatch in Layer Function " + dims0.toString + " and " + dims1.toString);
        } else if (i0 < dims0.length && i1 < dims1.length && dims0(i0) != dims1(i1)) {
        	throw new RuntimeException("dimensions mismatch in Layer Function " + dims0.toString + " and " + dims1.toString);           
        }
        i0 += 1;
        i1 += 1;
      }
    }
  }
  
  def applyfwd(a:Mat, ifn:Int):Mat = applyfwd(a, null, ifn);
  
  def applyfwd(a:Mat, out:Mat, ifn:Int):Mat = {
    Mat.nflops += 1L * a.length * fwdflops(ifn);
    checkdims(a.dims, out.dims);
    a match {
      case ag:GMat => {
        val oMat = GMat.newOrCheckGMat(a.dims, out, a.GUID, ifn, "LayerFn".##);
        CUMACH.applyfwd(ag.pdata, oMat.pdata, ifn, a.length);
        oMat
      }
      case af:FMat => {
        val oMat = FMat.newOrCheckFMat(a.dims, out, a.GUID, ifn, "LayerFn".##);
        CPUMACH.applyfwd(af.data, oMat.data, ifn, a.length, Mat.numThreads);
        oMat
      }
    }
  }

  def applyderiv(a:Mat, b:Mat, ifn:Int):Mat = applyderiv(a, b, null, ifn)
      
  def applyderiv(a:Mat, b:Mat, out:Mat, ifn:Int):Mat = {
	  Mat.nflops += 1L * a.length * bwdflops(ifn);
	  checkdims(a.dims, b.dims);
    (a, b) match {
      case (ag:GMat, bg:GMat) => {
        val oMat = GMat.newOrCheckGMat(a.dims, out, a.GUID, ifn, "LayerFn".##);
        CUMACH.applyderiv(ag.pdata, bg.pdata, oMat.pdata, ifn, a.length);
        oMat
      }
      case (af:FMat, bf:FMat) => {
        val oMat = FMat.newOrCheckFMat(a.dims, out, a.GUID, ifn, "LayerFn".##);
        CPUMACH.applyderiv(af.data, bf.data, oMat.data, ifn, a.length, Mat.numThreads);
        oMat
      }
    }
  }
}
 


