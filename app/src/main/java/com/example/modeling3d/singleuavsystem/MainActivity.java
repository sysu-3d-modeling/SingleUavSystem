package com.example.modeling3d.singleuavsystem;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

import dji.common.camera.SettingsDefinitions;
import dji.common.error.DJIError;
import dji.common.product.Model;
import dji.common.util.CommonCallbacks;
import dji.log.DJILog;
import dji.sdk.base.BaseProduct;
import dji.sdk.camera.Camera;
import dji.sdk.camera.VideoFeeder;
import dji.sdk.codec.DJICodecManager;
import dji.sdk.media.DownloadListener;
import dji.sdk.media.FetchMediaTask;
import dji.sdk.media.FetchMediaTaskContent;
import dji.sdk.media.FetchMediaTaskScheduler;
import dji.sdk.media.MediaFile;
import dji.sdk.media.MediaManager;
import dji.thirdparty.afinal.core.AsyncTask;
import com.example.modeling3d.singleuavsystem.media.DJIVideoStreamDecoder;

import com.example.modeling3d.singleuavsystem.customview.OverlayView;
import com.example.modeling3d.singleuavsystem.customview.OverlayView.DrawCallback;
import com.example.modeling3d.singleuavsystem.media.NativeHelper;
import com.example.modeling3d.singleuavsystem.tflite.Classifier;
import com.example.modeling3d.singleuavsystem.tflite.TFLiteObjectDetectionAPIModel;
import com.example.modeling3d.singleuavsystem.tracking.MultiBoxTracker;
import com.example.modeling3d.singleuavsystem.env.Logger;
import com.example.modeling3d.singleuavsystem.env.ImageUtils;

public class MainActivity extends AppCompatActivity implements DJICodecManager.YuvDataCallback{

    private static final String TAG = MainActivity.class.getName();

    protected Button downloadBtn = null;

    private List<MediaFile> mediaFileList = new ArrayList<MediaFile>();
    private MediaManager mMediaManager;
    private MediaManager.FileListState currentFileListState = MediaManager.FileListState.UNKNOWN;
    private FetchMediaTaskScheduler scheduler;
    private ProgressDialog mLoadingDialog;

    private ProgressDialog mDownloadDialog;
    private int currentProgress = -1;

    /***
     * detection var
     */

    private static final Logger LOGGER = new Logger();
    // Configuration values for the prepackaged SSD model.
    private static final int TF_OD_API_INPUT_SIZE = 300;
    private static final boolean TF_OD_API_IS_QUANTIZED = true;
    private static final String TF_OD_API_MODEL_FILE = "detect.tflite";
    private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/labelmap.txt";
    private static final DetectorMode MODE = DetectorMode.TF_OD_API;
    // Minimum detection confidence to track a detection.
    private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;
    private static final boolean MAINTAIN_ASPECT = false;
    private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
    private static final boolean SAVE_PREVIEW_BITMAP = false;
    private static final float TEXT_SIZE_DIP = 10;
    OverlayView trackingOverlay;
    private Integer sensorOrientation;

    private Classifier detector;

    private long lastProcessingTimeMs;
    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private boolean computingDetection = false;

    private long timestamp = 0;

    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    private MultiBoxTracker tracker;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Handler handler;
    private HandlerThread handlerThread;

    private Runnable imageConverter;
    private Runnable postInferenceCallback;

    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;

    private TextureView videostreamPreviewTtView;
    private SurfaceView videostreamPreviewSf;
    private SurfaceHolder videostreamPreviewSh;
    private Camera mCamera;
    private DJICodecManager mCodecManager;
    private TextView savePath;
    private Button screenShot;
    private StringBuilder stringBuilder;
    private int videoViewWidth;
    private int videoViewHeight;
    private int count;

    private static final int MSG_WHAT_SHOW_TOAST = 0;
    private static final int MSG_WHAT_UPDATE_TITLE = 1;
    private SurfaceHolder.Callback surfaceCallback;
    private enum DemoType { USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER}
    private static DemoType demoType = DemoType.USE_TEXTURE_VIEW;
    private VideoFeeder.VideoFeed standardVideoFeeder;

    protected VideoFeeder.VideoDataListener mReceivedVideoDataListener = null;
    private TextView titleTv;
    public Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_WHAT_SHOW_TOAST:
                    Toast.makeText(getApplicationContext(), (String) msg.obj, Toast.LENGTH_SHORT).show();
                    break;
                case MSG_WHAT_UPDATE_TITLE:
                    if (titleTv != null) {
                        titleTv.setText((String) msg.obj);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // When the compile and target version is higher than 22, please request the
        // following permissions at runtime to ensure the
        // SDK work well.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.VIBRATE,
                    Manifest.permission.INTERNET, Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.WAKE_LOCK, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_NETWORK_STATE, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CHANGE_WIFI_STATE, Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                    Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.SYSTEM_ALERT_WINDOW,
                    Manifest.permission.READ_PHONE_STATE,
                }
                , 1);
        }

        setContentView(R.layout.activity_main);

        downloadBtn = findViewById(R.id.downloadBtn);


        //initMediaManager();
        //Init Download Dialog
        mDownloadDialog = new ProgressDialog(MainActivity.this);
        mDownloadDialog.setTitle("Downloading file");
        mDownloadDialog.setIcon(android.R.drawable.ic_dialog_info);
        mDownloadDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mDownloadDialog.setCanceledOnTouchOutside(false);
        mDownloadDialog.setCancelable(true);
        mDownloadDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
            if (mMediaManager != null) {
                mMediaManager.exitMediaDownloading();
            }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        initSurfaceOrTextureView();
        notifyStatusChange();
    }

    @Override
    protected void onPause() {
        if (mCamera != null) {
            if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                VideoFeeder.getInstance().getPrimaryVideoFeed().removeVideoDataListener(mReceivedVideoDataListener);
            }
            if (standardVideoFeeder != null) {
                standardVideoFeeder.removeVideoDataListener(mReceivedVideoDataListener);
            }
        }
        super.onPause();
    }

    private long lastupdate;
    private void notifyStatusChange() {

        final BaseProduct product = VideoDecodingApplication.getProductInstance();

        Log.d(TAG, "notifyStatusChange: " + (product == null ? "Disconnect" : (product.getModel() == null ? "null model" : product.getModel().name())));
        if (product != null && product.isConnected() && product.getModel() != null) {
            updateTitle(product.getModel().name() + " Connected " + demoType.name());
        } else {
            updateTitle("Disconnected");
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener = new VideoFeeder.VideoDataListener() {

            @Override
            public void onReceive(byte[] videoBuffer, int size) {
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(TAG, "camera recv video data size: " + size);
                    lastupdate = System.currentTimeMillis();
                }
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        /**
                         we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.getInstance().parse(videoBuffer, size);
                        break;

                    case USE_TEXTURE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.sendDataToDecoder(videoBuffer, size);
                        }
                        break;
                }

            }
        };

        if (null == product || !product.isConnected()) {
            mCamera = null;
            showToast("Disconnected");
        } else {
            if (!product.getModel().equals(Model.UNKNOWN_AIRCRAFT)) {
                mCamera = product.getCamera();
                mCamera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
                    @Override
                    public void onResult(DJIError djiError) {
                        if (djiError != null) {
                            showToast("can't change mode of camera, error:"+djiError.getDescription());
                        }
                    }
                });

                if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER) {
                    if (VideoFeeder.getInstance() != null) {
                        standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed();
                        standardVideoFeeder.addVideoDataListener(mReceivedVideoDataListener);
                    }
                } else {
                    if (VideoFeeder.getInstance().getPrimaryVideoFeed() != null) {
                        VideoFeeder.getInstance().getPrimaryVideoFeed().addVideoDataListener(mReceivedVideoDataListener);
                    }
                }
            }
        }
    }


    private void initSurfaceOrTextureView(){
        switch (demoType) {
            case USE_SURFACE_VIEW:
                initPreviewerSurfaceView();
                break;
            case USE_SURFACE_VIEW_DEMO_DECODER:
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView();

                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView();
                break;
            case USE_TEXTURE_VIEW:
                initPreviewerTextureView();
                break;
        }
    }

    @Override
    protected void onDestroy() {

        SingleApplication.getCameraInstance().setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO, new CommonCallbacks.CompletionCallback() {
            @Override
            public void onResult(DJIError mError) {
                if (mError != null){
                    setResultToToast("Set Shoot Photo Mode Failed" + mError.getDescription());
                }
            }
        });

        if (mediaFileList != null) {
            mediaFileList.clear();
        }

        if (mCodecManager != null) {
            mCodecManager.cleanSurface();
            mCodecManager.destroyCodec();
        }
        super.onDestroy();
    }

//    private MediaManager.FileListStateListener updateFileListStateListener = new MediaManager.FileListStateListener() {
//        @Override
//        public void onFileListStateChange(MediaManager.FileListState state) {
//        currentFileListState = state;
//        }
//    };

//    private void initMediaManager() {
//        if (SingleApplication.getProductInstance() == null) {
//            mediaFileList.clear();
//            DJILog.e(TAG, "Product disconnected");
//            return;
//        } else {
//            if (null != SingleApplication.getCameraInstance() && SingleApplication.getCameraInstance().isMediaDownloadModeSupported()) {
//                mMediaManager = SingleApplication.getCameraInstance().getMediaManager();
//                if (null != mMediaManager) {
//                    mMediaManager.addUpdateFileListStateListener(this.updateFileListStateListener);
//                    SingleApplication.getCameraInstance().setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD, new CommonCallbacks.CompletionCallback() {
//                        @Override
//                        public void onResult(DJIError error) {
//                        if (error == null) {
//                            DJILog.e(TAG, "Set cameraMode success");
//                            showProgressDialog();
//                            getFileList();
//                        } else {
//                            setResultToToast("Set cameraMode failed");
//                        }
//                        }
//                    });
//                    if (mMediaManager.isVideoPlaybackSupported()) {
//                        DJILog.e(TAG, "Camera support video playback!");
//                    } else {
//                        setResultToToast("Camera does not support video playback!");
//                    }
//                    scheduler = mMediaManager.getScheduler();
//                }
//            } else if (null != SingleApplication.getCameraInstance()
//                    && !SingleApplication.getCameraInstance().isMediaDownloadModeSupported()) {
//                setResultToToast("Media Download Mode not Supported");
//            }
//        }
//        return;
//    }

//    private void getFileList() {
//        mMediaManager = SingleApplication.getCameraInstance().getMediaManager();
//        if (mMediaManager != null) {
//
//            if ((currentFileListState == MediaManager.FileListState.SYNCING) || (currentFileListState == MediaManager.FileListState.DELETING)){
//                DJILog.e(TAG, "Media Manager is busy.");
//            }else{
//
//                mMediaManager.refreshFileListOfStorageLocation(SettingsDefinitions.StorageLocation.SDCARD, new CommonCallbacks.CompletionCallback() {
//
//                    @Override
//                    public void onResult(DJIError djiError) {
//                    if (null == djiError) {
//                        hideProgressDialog();
//
//                        //Reset data
//                        if (currentFileListState != MediaManager.FileListState.INCOMPLETE) {
//                            mediaFileList.clear();
//                        }
//
//                        mediaFileList = mMediaManager.getSDCardFileListSnapshot();
//                        Collections.sort(mediaFileList, new Comparator<MediaFile>() {
//                            @Override
//                            public int compare(MediaFile lhs, MediaFile rhs) {
//                                if (lhs.getTimeCreated() < rhs.getTimeCreated()) {
//                                    return 1;
//                                } else if (lhs.getTimeCreated() > rhs.getTimeCreated()) {
//                                    return -1;
//                                }
//                                return 0;
//                            }
//                        });
//                        scheduler.resume(new CommonCallbacks.CompletionCallback() {
//                            @Override
//                            public void onResult(DJIError error) {
//                                if (error == null) {
//                                    getThumbnails();
//                                }
//                            }
//                        });
//                    } else {
//                        hideProgressDialog();
//                        setResultToToast("Get Media File List Failed:" + djiError.getDescription());
//                    }
//                    }
//                });
//            }
//        }
//    }
//
//    private void getThumbnailByIndex(final int index) {
//        FetchMediaTask task = new FetchMediaTask(mediaFileList.get(index), FetchMediaTaskContent.THUMBNAIL, taskCallback);
//        scheduler.moveTaskToEnd(task);
//    }
//
//    private void getThumbnails() {
//        if (mediaFileList.size() <= 0) {
//            setResultToToast("No File info for downloading thumbnails");
//            return;
//        }
//        for (int i = 0; i < mediaFileList.size(); i++) {
//            getThumbnailByIndex(i);
//        }
//    }
//
//    private FetchMediaTask.Callback taskCallback = new FetchMediaTask.Callback() {
//        @Override
//        public void onUpdate(MediaFile file, FetchMediaTaskContent option, DJIError error) {
//        if (null == error) {
//            if (option == FetchMediaTaskContent.PREVIEW) {
//                runOnUiThread(new Runnable() {
//                    public void run() {
//                        //mListAdapter.notifyDataSetChanged();
//                    }
//                });
//            }
//            if (option == FetchMediaTaskContent.THUMBNAIL) {
//                runOnUiThread(new Runnable() {
//                    public void run() {
//                        //mListAdapter.notifyDataSetChanged();
//                    }
//                });
//            }
//        } else {
//            DJILog.e(TAG, "Fetch Media Task Failed" + error.getDescription());
//        }
//        }
//    };

//    private void ShowDownloadProgressDialog() {
//        if (mDownloadDialog != null) {
//            runOnUiThread(new Runnable() {
//                public void run() {
//                mDownloadDialog.incrementProgressBy(-mDownloadDialog.getProgress());
//                mDownloadDialog.show();
//                }
//            });
//        }
//    }
//
//    private void HideDownloadProgressDialog() {
//
//        if (null != mDownloadDialog && mDownloadDialog.isShowing()) {
//            runOnUiThread(new Runnable() {
//                public void run() {
//                    mDownloadDialog.dismiss();
//                }
//            });
//        }
//    }

//    private void downloadFileByIndex(final int index){
//        if ((mediaFileList.get(index).getMediaType() == MediaFile.MediaType.PANORAMA)
//                || (mediaFileList.get(index).getMediaType() == MediaFile.MediaType.SHALLOW_FOCUS)) {
//            return;
//        }
//
//        mediaFileList.get(index).fetchFileData(destDir, null, new DownloadListener<String>() {
//            @Override
//            public void onFailure(DJIError error) {
//                HideDownloadProgressDialog();
//                setResultToToast("Download File Failed" + error.getDescription());
//                currentProgress = -1;
//            }
//
//            @Override
//            public void onProgress(long total, long current) {
//            }
//
//            @Override
//            public void onRateUpdate(long total, long current, long persize) {
//                int tmpProgress = (int) (1.0 * current / total * 100);
//                if (tmpProgress != currentProgress) {
//                    mDownloadDialog.setProgress(tmpProgress);
//                    currentProgress = tmpProgress;
//                }
//            }
//
//            @Override
//            public void onStart() {
//                currentProgress = -1;
//                ShowDownloadProgressDialog();
//            }
//
//            @Override
//            public void onSuccess(String filePath) {
//                HideDownloadProgressDialog();
//                setResultToToast("Download File Success" + ":" + filePath);
//                currentProgress = -1;
//            }
//        });
//    }

    private void setResultToToast(final String result) {
        runOnUiThread(new Runnable() {
            public void run() {
            Toast.makeText(MainActivity.this, result, Toast.LENGTH_SHORT).show();
            }
        });
    }

//    private void showProgressDialog() {
//        runOnUiThread(new Runnable() {
//            public void run() {
//            if (mLoadingDialog != null) {
//                mLoadingDialog.show();
//            }
//            }
//        });
//    }
//
//    private void hideProgressDialog() {
//
//        runOnUiThread(new Runnable() {
//            public void run() {
//            if (null != mLoadingDialog && mLoadingDialog.isShowing()) {
//                mLoadingDialog.dismiss();
//            }
//            }
//        });
//    }

    private void initDetection(final Size size, final int rotation) {
        tracker = new MultiBoxTracker(this);

        int cropSize = TF_OD_API_INPUT_SIZE;

        try {
            detector =
                TFLiteObjectDetectionAPIModel.create(
                    getAssets(),
                    TF_OD_API_MODEL_FILE,
                    TF_OD_API_LABELS_FILE,
                    TF_OD_API_INPUT_SIZE,
                    TF_OD_API_IS_QUANTIZED);
        cropSize = TF_OD_API_INPUT_SIZE;
        } catch (final IOException e) {
            e.printStackTrace();
            LOGGER.e(e, "Exception initializing classifier!");
            Toast toast =
                Toast.makeText(
                    getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
            toast.show();
            finish();
        }

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

        frameToCropTransform =
            ImageUtils.getTransformationMatrix(
                previewWidth, previewHeight,
                cropSize, cropSize,
                sensorOrientation, MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);

        trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
        trackingOverlay.addCallback(
            new DrawCallback() {
                @Override
                public void drawCallback(final Canvas canvas) {
                    tracker.draw(canvas);

                }
            });

        tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private void initPreviewerTextureView() {
        videostreamPreviewTtView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable: width " + videoViewWidth + " height " + videoViewHeight);
                if (mCodecManager == null) {
                    mCodecManager = new DJICodecManager(getApplicationContext(), surface, width, height);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable2: width " + videoViewWidth + " height " + videoViewHeight);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (mCodecManager != null) {
                    mCodecManager.cleanSurface();
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {

            }
        });
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private void initPreviewerSurfaceView() {
        videostreamPreviewSh = videostreamPreviewSf.getHolder();
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "real onSurfaceTextureAvailable");
                videoViewWidth = videostreamPreviewSf.getWidth();
                videoViewHeight = videostreamPreviewSf.getHeight();
                Log.d(TAG, "real onSurfaceTextureAvailable3: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager == null) {
                            mCodecManager = new DJICodecManager(getApplicationContext(), holder, videoViewWidth,
                                    videoViewHeight);
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.getInstance().init();
                        DJIVideoStreamDecoder.getInstance().init(getApplicationContext(), holder.getSurface());
                        DJIVideoStreamDecoder.getInstance().resume();
                        break;
                }

            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                videoViewWidth = width;
                videoViewHeight = height;
                Log.d(TAG, "real onSurfaceTextureAvailable4: width " + videoViewWidth + " height " + videoViewHeight);
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        //mCodecManager.onSurfaceSizeChanged(videoViewWidth, videoViewHeight, 0);
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().changeSurface(holder.getSurface());
                        break;
                }

            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                switch (demoType) {
                    case USE_SURFACE_VIEW:
                        if (mCodecManager != null) {
                            mCodecManager.cleanSurface();
                            mCodecManager.destroyCodec();
                            mCodecManager = null;
                        }
                        break;
                    case USE_SURFACE_VIEW_DEMO_DECODER:
                        DJIVideoStreamDecoder.getInstance().stop();
                        NativeHelper.getInstance().release();
                        break;
                }

            }
        };

        videostreamPreviewSh.addCallback(surfaceCallback);
    }

    @Override
    public void onYuvDataReceived(final ByteBuffer yuvFrame, int dataSize, final int width, final int height) {
        if (isProcessingFrame) {
            LOGGER.w("Dropping frame!");
            return;
        }

        try {
            // Initialize the storage bitmaps once when the resolution is known.
            if (rgbBytes == null) {
                previewHeight = height;
                previewWidth = width;
                rgbBytes = new int[previewWidth * previewHeight];
                initDetection(new Size(width, height), 90);
            }
        } catch (final Exception e) {
            LOGGER.e(e, "Exception!");
            return;
        }

        final byte[] bytes = new byte[dataSize];
        yuvFrame.get(bytes);

        isProcessingFrame = true;
        yuvBytes[0] = bytes;

        imageConverter =
                new Runnable() {
                    @Override
                    public void run() {
                        ImageUtils.convertYUV420SPToARGB8888(bytes, previewWidth, previewHeight, rgbBytes);
                    }
                };

        postInferenceCallback =
                new Runnable() {
                    @Override
                    public void run() {
                        //camera.addCallbackBuffer(bytes);
                        isProcessingFrame = false;
                    }
                };
        processImage();
    }

    protected void processImage() {
        ++timestamp;
        final long currTimestamp = timestamp;
        trackingOverlay.postInvalidate();

        // No mutex needed as this method is not reentrant.
        if (computingDetection) {
            readyForNextImage();
            return;
        }
        computingDetection = true;
        LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

        rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

        readyForNextImage();

        final Canvas canvas = new Canvas(croppedBitmap);
        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
        // For examining the actual TF input.
        if (SAVE_PREVIEW_BITMAP) {
            ImageUtils.saveBitmap(croppedBitmap);
        }

        runInBackground(
            new Runnable() {
                @Override
                public void run() {
                    LOGGER.i("Running detection on image " + currTimestamp);
                    final long startTime = SystemClock.uptimeMillis();
                    final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);
                    lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

                    cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                    final Canvas canvas = new Canvas(cropCopyBitmap);
                    final Paint paint = new Paint();
                    paint.setColor(Color.RED);
                    paint.setStyle(Style.STROKE);
                    paint.setStrokeWidth(2.0f);

                    float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                    switch (MODE) {
                        case TF_OD_API:
                            minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                            break;
                    }

                    final List<Classifier.Recognition> mappedRecognitions =
                            new LinkedList<Classifier.Recognition>();

                    for (final Classifier.Recognition result : results) {
                        final RectF location = result.getLocation();
                        if (location != null && result.getConfidence() >= minimumConfidence) {
                            canvas.drawRect(location, paint);

                            cropToFrameTransform.mapRect(location);

                            result.setLocation(location);
                            mappedRecognitions.add(result);
                        }
                    }

                    tracker.trackResults(mappedRecognitions, currTimestamp);
                    trackingOverlay.postInvalidate();

                    computingDetection = false;
                }
            });
    }

    protected int[] getRgbBytes() {
        imageConverter.run();
        return rgbBytes;
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    private enum DetectorMode {
        TF_OD_API;
    }

    private void showToast(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        );
    }

    private void updateTitle(String s) {
        mainHandler.sendMessage(
                mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        );
    }

}
