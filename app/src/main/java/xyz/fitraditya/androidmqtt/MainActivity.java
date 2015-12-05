package xyz.fitraditya.androidmqtt;

import android.support.v7.app.AppCompatActivity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static String PREFS     = "mqtt-prefs";
    private static String HOSTNAME  = "-Hostname";
    private static String PORT      = "-Port";
    private static String USERNAME  = "-Username";
    private static String PASSWORD  = "-Password";
    private static String TOPIC     = "-Topic";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        final SharedPreferences sharedPref = this.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final EditText vHostname, vTopic, vUsername, vPassword, vPort;
        vHostname	= (EditText) findViewById(R.id.mHost);
        vPort		= (EditText) findViewById(R.id.mPort);
        vUsername	= (EditText) findViewById(R.id.mUsername);
        vPassword	= (EditText) findViewById(R.id.mPassword);
        vTopic		= (EditText) findViewById(R.id.mTopic);

        String sHostname, sTopic, sUsername, sPassword, sPort;
        sHostname	= sharedPref.getString(HOSTNAME, "");
        sTopic		= sharedPref.getString(TOPIC,"");
        sUsername	= sharedPref.getString(USERNAME, "");
        sPassword	= sharedPref.getString(PASSWORD, "");
        sPort		= sharedPref.getString(PORT, "");

        vHostname.setText(sHostname);
        vPort.setText(String.valueOf(sPort));
        vUsername.setText(sUsername);
        vPassword.setText(sPassword);
        vTopic.setText(sTopic);

        final Button button = (Button) findViewById(R.id.mSave);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (vHostname.getText().toString().length() > 0 &&
                        vTopic.getText().toString().length() > 0 &&
                        vPort.getText().toString().length() > 0) {
                    SharedPreferences.Editor editor = sharedPref.edit();
                    editor.putString(HOSTNAME, vHostname.getText().toString());
                    editor.putString(TOPIC, vTopic.getText().toString());
                    editor.putString(USERNAME, vUsername.getText().toString());
                    editor.putString(PASSWORD, vPassword.getText().toString());
                    editor.putString(PORT, vPort.getText().toString());
                    editor.commit();

                    final Intent intent = new Intent(getApplicationContext(), MQTTService.class);
                    startService(intent);
                    finish();
                } else {
                    Toast.makeText(getApplicationContext(), "Please Fill Hostname, Port, and Topic", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

}
