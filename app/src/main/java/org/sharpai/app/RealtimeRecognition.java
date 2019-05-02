package org.sharpai.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.squareup.picasso.OkHttp3Downloader;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sharpai.termux.R;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RealtimeRecognition implements MqttCallback {
	private MqttClient mqttClient;

	public static final String BROKER_URL = "tcp://localhost:1883";
	private boolean mConnectedToBroker = false;

	public static final String TOPIC = "rt_message";
	public static final String TAG = "RT_Message";

	private MqttCallback mMqttCallback;
	private ImageView mStatusView;
    private ImageView mPersonView;
    private Target mPersonTarget;
    private Handler mMainHandler;
    private Context mContext;
    private Picasso mPicasso;
    private TextView mPersonTextView;
    private SystemTTS mSystemTTS;

    private long mPreviousTTSMs = 0;
    private static final long DURATION_BETWEEN_TTS = 30*1000;

    OkHttpClient okHttpClient = new OkHttpClient.Builder()
        .addInterceptor(new Interceptor() {
            @Override
            public Response intercept(Interceptor.Chain chain) throws IOException {
                Request request = chain.request();
                Response response = null;
                response = chain.proceed(request);
                int tryCount = 0;
                while ((response == null || !response.isSuccessful()) && tryCount < 5) {
                    Log.d(TAG,"retrying");
                    tryCount++;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    response = chain.proceed(request);
                }
                return response;
            }
        })
        .build();

    public RealtimeRecognition(Context context,ImageView statusView,ImageView personView,
                               TextView textView) {
        //super.onCreate(savedInstanceState);
        mMqttCallback = this;
        mStatusView = statusView;
        mPersonView = personView;
        mPersonTextView = textView;
        mContext = context;

        mSystemTTS = SystemTTS.getInstance(mContext);

        mPicasso = new Picasso
            .Builder(mContext)
            .downloader(new OkHttp3Downloader(okHttpClient))
            .build();

        mMainHandler = new Handler(Looper.getMainLooper());
        Picasso.setSingletonInstance(mPicasso);
        mPersonTarget = new Target() {
            @Override
            public void onBitmapLoaded(Bitmap bitmap, Picasso.LoadedFrom from) {
                mPersonView.setImageBitmap(bitmap);
            }

            @Override
            public void onBitmapFailed(Exception e, Drawable errorDrawable) {

            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {
            }
        };
        SetOff();
    }

    private void SetOff(){
        mStatusView.setImageResource(R.drawable.green_off);
    }
    private void SetGreen(){
        //Format reference:
        // https://stackoverflow.com/questions/5369682/get-current-time-and-date-on-android
        DateFormat df = new SimpleDateFormat("HH:mm:ss");

        String date = df.format(Calendar.getInstance().getTime());
        mPersonTextView.setText(date);
        mStatusView.setImageResource(R.drawable.green_on);
    }
    private void setKnownPersonImage(String imageUrl){
        if(imageUrl == null){
            return;
        }
        if(imageUrl.equals("")){
            return;
        }
        mMainHandler.post(new Runnable(){
            @Override
            public void run() {
                mPicasso
                    .load(imageUrl)
                    .into(mPersonTarget);
                //Picasso.with(mContext).load(formatStaticImageUrlWith(location)).transform(new StaticMapTransformation()).into(remoteView, R.id.static_map_image, Constants.NOTIFICATION_ID_NEW_TRIP_OFFER, notification);
            }
        });
        //mPersonView.setImageURI(new Uri(imageUrl));
    }
    public void Start(){
        startIntervalForMQTTReconnect();
    }
    private String getKnownPersonImage(JSONObject json){
        try {
            JSONArray persons = json.getJSONArray("persons");
            JSONObject person = (JSONObject) persons.get(0);
            if(person != null){
                String imageUrl = person.getString("img_url");
                if(imageUrl != null && !imageUrl.equals("")){
                    Log.d(TAG,"image of known person "+imageUrl);
                    return imageUrl;
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }
	@Override
	public void connectionLost(Throwable cause) {
		Log.d(TAG,"connectionLost");
        mConnectedToBroker = false;

		SetOff();
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		Log.d(TAG,message.toString());
		if(topic.equals("rt_message")){
			String status = "";
            JSONObject mainObject = null;
			try{
				mainObject = new JSONObject(message.toString());
				status = mainObject.getString("status");// mainObject.getJsonString("name");
			} catch (JSONException e){
				Log.e(TAG,"exception of JSONException");
			}
			if(status.equals("known person")){
				Log.i(TAG,"known person");

                setKnownPersonImage(getKnownPersonImage(mainObject));

				SetGreen();
				Timer myTimer = new Timer();
				myTimer.schedule(new TimerTask() {
					@Override
					public void run() {
						SetOff();
						this.cancel();
					}

				}, 2000, 1000);

                if( System.currentTimeMillis()-mPreviousTTSMs >= DURATION_BETWEEN_TTS){
                    mPreviousTTSMs = System.currentTimeMillis();
                    mSystemTTS.play("你好");
                }

			} else if(status.equals("Stranger")){
				Log.i(TAG,"Stranger");
			}
		} else if(topic.equals("test")){
			Log.i(TAG,"Test");
            mConnectedToBroker = true;
			SetGreen();
			Timer myTimer = new Timer();
			myTimer.schedule(new TimerTask() {
				@Override
				public void run() {
					SetOff();
					this.cancel();
				}

			}, 2000, 1000);
		}
		//message.toString().
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken token) {
	}

	private void reConnectToMQTT(){
        mConnectedToBroker = false;
        try {
            MemoryPersistence persistence = new MemoryPersistence();
            mqttClient = new MqttClient(BROKER_URL,MqttClient.generateClientId(),persistence);
            mqttClient.setCallback(mMqttCallback);
            mqttClient.connect();
            mqttClient.subscribe(TOPIC);
            mqttClient.subscribe("test");
            final MqttMessage message = new MqttMessage("testing".getBytes());
            mqttClient.publish("test",message);
        } catch (MqttException e) {
            e.printStackTrace();
            mConnectedToBroker = false;
        }

	}
    private void startIntervalForMQTTReconnect(){
        Log.d(TAG,"start interval for mqtt reconnection");
        Timer myTimer = new Timer();
        myTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if(mConnectedToBroker == false){
                    reConnectToMQTT();
                }
            }

        }, 1000, 6000);
    }
}
