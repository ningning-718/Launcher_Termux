package org.sharpai.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.UtteranceProgressListener;
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private String mTTSMessageID = null;

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

        mSystemTTS = SystemTTS.getInstance(mContext,new UtteranceProgressListener() {
            @Override
            public void onDone(String utteranceId) {
                // Log.d("MainActivity", "TTS finished");
                Log.d(TAG,mTTSMessageID+" speaking done "+utteranceId);
                if(mTTSMessageID!=null){
                    JSONObject json = new JSONObject();
                    try {
                        json.put("_id", mTTSMessageID);
                    } catch (JSONException e) {
                        e.printStackTrace();
                        return;
                    }

                    String response = makeRequest("http://127.0.0.1:3380/person_message_done",json.toString());
                    Log.d(TAG,"Msg read result "+response);
                    mTTSMessageID = null;
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.d(TAG,mTTSMessageID + "speaking error "+utteranceId);
            }

            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG,mTTSMessageID + "speaking started "+utteranceId);
            }
        });

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
        try{
            mStatusView.setImageResource(R.drawable.green_off);
        } catch(Exception e){
            e.printStackTrace();
            Log.e(TAG,"Exception in SetOff "+e.toString());
        }
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
    public static String makeRequest(String uri, String json) {
        HttpURLConnection urlConnection;
        String url;
        String data = json;
        String result = null;
        try {
            //Connect
            urlConnection = (HttpURLConnection) ((new URL(uri).openConnection()));
            urlConnection.setDoOutput(true);
            urlConnection.setRequestProperty("Content-Type", "application/json");
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestMethod("POST");
            urlConnection.connect();

            //Write
            OutputStream outputStream = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
            writer.write(data);
            writer.close();
            outputStream.close();

            //Read
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));

            String line = null;
            StringBuilder sb = new StringBuilder();

            while ((line = bufferedReader.readLine()) != null) {
                sb.append(line);
            }

            bufferedReader.close();
            result = sb.toString();

        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }
    private JSONObject getMsgFromJson(String str){
        //{
        // "result":"yes",
        // "msg":{
        //     "_id":"Ncf8tK3rQAdFFSPHz",
        //     "msg":"波刚才找过你",
        //     "personId":"78d2e0ea24b1da2ec68b2e66",
        //     "groupId":"e87a2fa20f1052b659f2decc",
        //     "isRead":false,
        //     "createdAt":"2019-05-06T17:44:06.102Z"
        //   }
        // }

        JSONObject obj = null;
        JSONObject msg = null;
        try {
            obj = new JSONObject(str);
            if(!obj.getString("result").equals("yes")){
                return null;
            }
            msg = obj.getJSONObject("msg");
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

        return msg;
    }
	private void speakToKnownPerson(JSONObject obj){
        if(mSystemTTS.isSpeaking()){
            Log.d(TAG,"is playing can't say anything");
            return;
        }
        String face_id = null;
        JSONObject json = new JSONObject();
        JSONObject msg = null;
        try {
            // This is caused by long history.
            face_id = obj.getString("person_id");
            json.put("face_id", face_id);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        String result = "";
        String response = makeRequest("http://127.0.0.1:3380/person_message_unread",json.toString());
        if(response == null){
            Log.d(TAG,"Error of rest post");
            return;
        }

        try {
            JSONObject jsonObject = new JSONObject(response);
            result = jsonObject.getString("result");
            Log.v(TAG,"Response of detector is "+response);

            if(result.equals("yes")){
                msg = getMsgFromJson(response);
                if(msg != null && msg.getString("msg").equals("") == false){
                    mTTSMessageID = msg.getString("_id");
                    if(mSystemTTS.isSpeaking() != true) {
                        mSystemTTS.play(msg.getString("msg"));
                        return;
                    }
                }
            } else if(result.equals("name")){
                if(mSystemTTS.isSpeaking() != true){
                    mPreviousTTSMs = System.currentTimeMillis();
                    String name = jsonObject.getString("name");
                    if(name != null && !name.equals("")){
                        String toPlay = name+",您好";
                        mSystemTTS.play(toPlay);
                        Log.d(TAG,"To TTS "+toPlay);
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            Log.d(TAG,"Exception in speakToKnownPerson");
        }

        return ;
    }
    private void testForKnowPerson(){
        String face_id = "15392942339300000";

        JSONObject json = new JSONObject();
        try {
            //person_id from realtime api is faceId.
            json.put("person_id", face_id);
        } catch (JSONException e) {
            e.printStackTrace();
            return;
        }

        speakToKnownPerson(json);

        return;
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
                speakToKnownPerson(mainObject);
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

        /*
        Timer myTimer1 = new Timer();
        myTimer1.schedule(new TimerTask() {
            @Override
            public void run() {
                testForKnowPerson();
            }

        }, 1000, 6000);
        */
    }
}
