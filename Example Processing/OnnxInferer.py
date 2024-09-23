import onnxruntime
import cv2
import numpy as np

def init_session(model_path):
    EP_list = [
        ('TensorrtExecutionProvider', {
            'device_id': 0,
            'trt_max_workspace_size': 2147483648,
            'trt_fp16_enable': True,
            'trt_engine_cache_enable': True,
            "trt_engine_cache_path" : r"./trt_engines/"
            'verbose'
        }),
        ('CUDAExecutionProvider', {
            'device_id': 0,
            'arena_extend_strategy': 'kNextPowerOfTwo',
            'gpu_mem_limit': 2 * 1024 * 1024 * 1024,
            'cudnn_conv_algo_search': 'EXHAUSTIVE',
            'do_copy_in_default_stream': True,
        }),
        ('CPUExecutionProvider')
    ]
    so = onnxruntime.SessionOptions()
    if not "fp16" in model_path:
        so.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_ALL
    else:
        so.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_BASIC
    sess = onnxruntime.InferenceSession(model_path,providers=EP_list[1:],sess_options=so) #TODO skips tensorrt
    return sess

class PickableInferenceSession: # This is a wrapper to make the current InferenceSession class pickable.
    def __init__(self, model_path):
        self.model_path = model_path
        self.sess = init_session(self.model_path)

    def run(self, *args):
        return self.sess.run(*args)

    def __getstate__(self):
        return {'model_path': self.model_path}

    def __setstate__(self, values):
        self.model_path = values['model_path']
        self.sess = init_session(self.model_path)

class ORTInference:
    #this is an abstract class that needs to be overwritten accordingly
    def __init__(self,onnx_file):
        self.onnx_file = onnx_file
        self.ort_session = PickableInferenceSession(onnx_file)
        self.inp_name = self.ort_session.sess.get_inputs()[0].name
        self.out_names = [nd.name for nd in self.ort_session.sess.get_outputs()]
        self.h_in = self.ort_session.sess.get_inputs()[0].shape[2]
        self.w_in = self.ort_session.sess.get_inputs()[0].shape[3]

    def __call__(self,image):
        return self.core_process(image)

    def input_to_model(self,x):
        x = cv2.resize(x,(self.w_in,self.h_in))
        return np.expand_dims(np.array(np.transpose(x[:,:,::-1],(2,0,1))).astype("float32")/255,axis=0) #Converts BGR to RGB

    def fp16_32(self,x):
        return x.astype("float16") if "fp16" in self.onnx_file else x.astype("float32")

    def out_from_model(self,*args):
        raise NotImplemented

    def post_process(self,*args):
        return np.clip(args[0]*255,0,255).astype(np.uint8)

    def core_process(self,image):
        ort_inputs = {self.inp_name: self.fp16_32(self.input_to_model(image))}
        # logger.info("videoProcessor: in ort_session")
        out = self.out_from_model(*self.ort_session.run(self.out_names, ort_inputs))
        return self.post_process(out)


class DepthEstimator(ORTInference):
    def __init__(self,onnx_file):
        super(DepthEstimator, self).__init__(onnx_file)

    def input_to_model(self,x):
        return np.expand_dims(np.array(np.transpose(x[:,:,::-1],(2,0,1))).astype("float32")/255,axis=0) #Converts BGR to RGB

    def out_from_model(self, output):
        return output[0]
