package com.CellIdTrack.main;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import android.os.SystemClock;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
//import android.widget.Toast;
import android.app.PendingIntent;
import android.app.AlarmManager;
import java.lang.Thread;
import android.app.Notification;


	
public class CellIdService extends Service {
	private static final String TAG = "CellIdService";
	Thread thr;
	public boolean ThreadMustStop = false;
	//private WakeLock mWakelock = null;
	 AlarmManager alarms;
     PendingIntent alarmIntent; 
	 int updateFreq = 60;//сек
	  public static final String NEW_FOUND = "New_Found";

	  private PowerManager pm;
	    private WakeLock CPULock;
	  
		private String filename = "ldata.txt";
		

	 private TelephonyManager Tel;
	private MyPhoneStateListener MyListener;
	 boolean ListenerIsExec = false;
	 

	 private long LastCellId = 0;
	 
	 private long LastLacId = 0;

	 public static volatile PowerManager.WakeLock lockStatic=null;

	 SMSOutMonitor sms;

  	 long NewCellId = 0; 
  	 long NewLacId = 0;
  	 double DistanceToLastCell = 0;
  	 boolean NeedToWrite = false;
  	 
  	 String outputText; 
	 
  	
	 
	 IBinder mBinder = new LocalBinder();

	 
	 /************************************************************************

	 ************************************************************************/
	 @Override public IBinder onBind(Intent intent) {
	     return mBinder;
	 }

	 /************************************************************************

	 ************************************************************************/
	 public class LocalBinder extends Binder {
	  public CellIdService getServerInstance() {
	   return CellIdService.this;
	  }
	 }
	
	 
	
 
	 /************************************************************************

	 ************************************************************************/
	@Override public void onCreate() {
		//Toast.makeText(this, "Сервис onCreate", Toast.LENGTH_SHORT).show();
		Log.d(TAG, "onCreate");

		saveDataToFile("Начало работы сервиса");
	    
		
		/*2012-01-29 sms = new SMSOutMonitor();
		  sms.SMSMonitor( this );
	      sms.startSMSMonitoring();
	      */
	      
	     pm = ((PowerManager) getSystemService(Context.POWER_SERVICE));
	     CPULock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "My Tag");
	     CPULock.acquire();
		

	 //    startForeground(1234, notice);
	     
	     
	     
	     
	     
		//Обеспечим асинхронный вызов процедуры при изменении параметров сети GSM
        MyListener = new MyPhoneStateListener();
	    Tel = (TelephonyManager) getSystemService( TELEPHONY_SERVICE);
	    Tel.listen(MyListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
	  

	    //Для того, чтобы сервис не отключался при выключении экрана телефона (и следовательно засыпания
	    //родительского процесса CellIdStart), нужно чтобы к сервису был привязан другой незасыпающий "сервис",
	    //который посылкой сообщения будет поддерживать живое подключение к сервису, а Андройд, следовательно, не будет убивать
	    //наш сервис
	    //1. Определим, как будет Аларм связываться с нашим сервисом - широковещательный запрос (см. также registerReceiver, без него запрос мы не получим)
	    alarms = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
		String ALARM_ACTION = AlarmReceiver.ACTION_REFRESH_ALARM;
		Intent intentToFire = new Intent(ALARM_ACTION);
		alarmIntent = PendingIntent.getBroadcast(this, 0, intentToFire, 0);
	    //укажем, что аларм должен циклично срабатывать 
	    int alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP;
	    long timeToRefresh = SystemClock.elapsedRealtime() + updateFreq*1000;
	    alarms.setRepeating(alarmType, timeToRefresh,updateFreq*1000, alarmIntent);
	   
        GetAndWriteCellId();
	    
        MakeThread();
  	}


	
	/************************************************************************

	************************************************************************/
	@Override public int onStartCommand(Intent intent, int flags, int startId) {
		//Toast.makeText(this, "Сервис onStartCommand", Toast.LENGTH_SHORT).show();
		super.onStartCommand(intent, flags, startId);
		
		//Разместив привязку к прослушке дополнительно здесь,  
		//Tel.listen(MyListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
		
	    return START_STICKY;
	}
	
	/************************************************************************

	************************************************************************/
	public void MakeThread(){
		//Toast.makeText(CellIdService.this, "MakeThread begin", Toast.LENGTH_SHORT).show();
	    thr = new Thread(null, backgroundRefresh, "ServiceCellIdHandler");
		thr.start();
	}
	

	/************************************************************************

	************************************************************************/
	@Override	public void onStart(Intent intent, int startid) {
		//Toast.makeText( this, "Service onStart", Toast.LENGTH_SHORT).show();
		Log.d(TAG, "onStart");
	}

	
	/************************************************************************

	************************************************************************/
 	@Override public void onDestroy() {
		//Toast.makeText( this, "Сервис остановлен", Toast.LENGTH_SHORT).show();
 		//2012-01-29 sms.stopSMSMonitoring();
		Log.d(TAG, "onDestroy");
		 CPULock.release();
		 stopForeground(true);
		Tel.listen(MyListener, PhoneStateListener.LISTEN_NONE);
		ThreadMustStop = true;
		thr.stop();
		alarms.cancel(alarmIntent);
		saveDataToFile("Завершение работы сервиса");
	}
	
 	
 	public  Context GetServiceContext(){
 		return this.getApplicationContext();
 	}

 	/************************************************************************
	//Проверку на изменения параметров сети GSM будем проверять в треде
	************************************************************************/
	private Runnable backgroundRefresh = new Runnable() {
		  public void run() {
		 		  
	  	    while ( ThreadMustStop != true ) {

	  	    	//Разбудим телефон, подождем когда определиться сота и дадим уснуть.
	  	    	 CPULock.acquire();
	  	    	 try{
	  	  	  	     Thread.sleep(5 * 1000);
	  	  	  	     }
	  	  	       catch(InterruptedException e){
	  	  	       }

	  	    	
	  	    	//Если обработчик изенения параметров сети GSM сработал, записать изменения
  	  	       if( ListenerIsExec ) CheckChangePosition();
  	  	      CPULock.release();

  	  	       
			    //SystemClock.sleep(10 * 1000);//Надо другой вызов засыпания.
  	  	       try{
  	  	       Thread.sleep(10 * 1000);//со скоростью 30 км/ч в среднем соты меняются раз в 2 минуты
  	  	       }
  	  	       catch(InterruptedException e){
  	  	       }
  	  	       
  	  	    		   
		     }   
		  }		  
			  
		};
		
		
/************************************************************************
//Запись текущих параметров сети в файл
************************************************************************/
public void CheckChangePosition(){
  if ( (NewCellId != LastCellId) || (NewLacId != LastLacId)  ) {
	LastCellId = NewCellId; 
	LastLacId  = NewLacId; 
	outputText  = "";

	//Формать записи в файл DTM,mcc+mnc,lac,cellid, 1/0 (1 инфо по вышке достоверна),расстояние
	outputText += Tel.getNetworkOperator()+','+String.valueOf(NewLacId)+','+String.valueOf(NewCellId);

	                  
	saveDataToFile(outputText);
  }
  ListenerIsExec = false;
}		
	
/************************************************************************
Запись в файл указанной строки с добавлением текущего времени
************************************************************************/
private void saveDataToFile(String LocalFileWriteBufferStr) {
        /* write measurement data to the output file */
   	    try {
   	       String sDTM  = DateFormat.getDateInstance().format(new Date()) + " ";
   	       sDTM += DateFormat.getTimeInstance().format(new Date()) + ", ";
   	 	
		    File root = Environment.getExternalStorageDirectory();
            if (root.canWrite()){
                File logfile = new File(root, filename);
                FileWriter logwriter = new FileWriter(logfile, true); /* true = append */
                BufferedWriter out = new BufferedWriter(logwriter);
                

                /* now save the data buffer into the file */
                out.write(sDTM + LocalFileWriteBufferStr+"\r\n");
                out.close();
            }
        }    
        catch (IOException e) {
        /* don't do anything for the moment */
        }
        
    }


/************************************************************************
//Обработчик системного события "изменение параметров сети GSM"
************************************************************************/
private class MyPhoneStateListener extends PhoneStateListener {
	 public void onSignalStrengthsChanged(SignalStrength signalStrength) {
	   GetAndWriteCellId();
   }
 
  };


 
/************************************************************************

************************************************************************/
	 public void GetAndWriteCellId() {
	  	 Log.d(TAG,"GetAndWriteCellId");
 	  	 try {
	  		 outputText = "";
	  		 //Формат запроса OpenCellId.org  mnc=%d&mcc=%d&lac=%d&cellid=%d
	           GsmCellLocation myLocation = (GsmCellLocation) Tel.getCellLocation();
	           if( myLocation != null ){
	             NewCellId = myLocation.getCid();  
	             NewLacId = myLocation.getLac();
	             ListenerIsExec = true;
	           };  
	  	 }
	  	 catch (Exception e) {
	  		 outputText = "No network information available..."; 
	  	 }


	    }
	 
}

