package com.ibm.ws.msdemo.service.disovery;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.json.JSONArray;
import org.json.JSONObject;

@WebListener
public class ServiceDiscovery implements ServletContextListener{
	private String sdAuthToken;
	private String sdBaseURL;

	@Override
	public void contextInitialized(ServletContextEvent arg0) {
		init();
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent arg0) {
		System.out.println("Goodbye!");
	}
	
	public void init(){
		// Register this application on start up
		System.out.println("Registering application with service discovery...");
		String[] sdCreds = getServiceDiscoveryCredentials();
		this.sdBaseURL = sdCreds[0];
		this.sdAuthToken = sdCreds[1];
		System.out.println("service discovery creds are: " + sdBaseURL + " " + sdAuthToken);
		try {
			register(sdBaseURL + "/api/v1/instances");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	// Send a POST request to service discovery service to register this applications URI under the name "Orders-API"
	private void register(String url) throws IOException{
		// Create the URL.
		URL orgsURL = new URL(url);
		HttpURLConnection con = (HttpURLConnection) orgsURL.openConnection();
		con.setRequestMethod("POST");
		con.setRequestProperty("Authorization", "Bearer " + sdAuthToken);
		con.setRequestProperty("Content-Type","application/json");
		con.setDoOutput(true);
		
		String serviceDiscoveryData = "{\"service_name\":\"Orders-API\"," +
				                       "\"endpoint\":{" +
				     				     "\"type\": \"tcp\"," +
				     				     "\"value\": \"" + getApplicationURI() +"\"" +
				     			         "}," +
				                       "\"ttl\": 60," +
				     			       "\"status\": \"UP\" " +
				     			       "}";
		
		// Add JSON POST data.
		DataOutputStream wr = new DataOutputStream(con.getOutputStream());
		wr.writeBytes(serviceDiscoveryData);
		wr.flush();
		wr.close();
		
		BufferedReader br = null;
		if (HttpURLConnection.HTTP_CREATED == con.getResponseCode() || HttpURLConnection.HTTP_OK == con.getResponseCode()) {
			br = new BufferedReader(new InputStreamReader(con.getInputStream()));
			StringBuffer response = new StringBuffer();
			String line;

			while ((line = br.readLine()) != null) {
				response.append(line);
			}
			br.close();
			JSONObject responseJSON = new JSONObject(response.toString());
			long ttl = (long) responseJSON.get("ttl");
			// We will poll the heartbeat url every x seconds where x is half of the time to live (ttl)
			long interval = Math.round((ttl * 1000) * .50);
			JSONObject links = (JSONObject) responseJSON.get("links");
			String heartbeatURL = (String) links.get("heartbeat");
			Timer timer = new Timer();
			timer.schedule(new RunHeartbeat(heartbeatURL, sdAuthToken), interval, interval);
		} 
		else {
			br = new BufferedReader(new InputStreamReader(con.getErrorStream()));
			StringBuffer response = new StringBuffer();
			String line;

			while ((line = br.readLine()) != null) {
				response.append(line);
			}
			br.close();

			// Response from the request.
			System.out.println("Bad service discovery register " + con.getResponseCode() + ": " + response.toString());
		}
	}
	
	private String getApplicationURI(){
		String appURI = "";
		String env = System.getenv("VCAP_APPLICATION");
		try {
			JSONObject vcap = new JSONObject(env);
			JSONArray appURIS = (JSONArray) vcap.get("application_uris");
			appURI = (String) appURIS.get(0);
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
		
		return appURI;
	}
	
	// Return array with baseURL at index 0 and authToken at index 1
	private String[] getServiceDiscoveryCredentials(){
		String [] result = new String [2];
		String env = System.getenv("VCAP_SERVICES");
		try {
			JSONObject vcap = new JSONObject(env);
			JSONArray appURIS = (JSONArray) vcap.get("service_discovery");
			JSONObject sd = (JSONObject) appURIS.get(0);
			JSONObject sdCredentials = (JSONObject) sd.get("credentials");
			result[0] = (String) sdCredentials.get("url");
			result[1] = (String) sdCredentials.get("auth_token");
		} catch (NullPointerException e) {
			e.printStackTrace();
		}
		
		return result;
	}

}

class RunHeartbeat extends TimerTask{
	private String heartbeatURL;
	private String sdAuthToken;
	
	RunHeartbeat(String heartbeatURL, String sdAuthToken){
		this.heartbeatURL = heartbeatURL;
		this.sdAuthToken = sdAuthToken;
	}
	
	@Override
	public void run() {
		try{
			// Send a PUT request to the heartbeat URL so the service discovery service knows we're still alive
			URL orgsURL = new URL(heartbeatURL);
			HttpURLConnection con = (HttpURLConnection) orgsURL.openConnection();
			con.setRequestProperty("Authorization", "Bearer " + sdAuthToken);
			con.setRequestProperty("Content-Length", "0");
			con.setRequestMethod("PUT");
			con.setDoOutput(true);
			
			OutputStreamWriter out = new OutputStreamWriter(con.getOutputStream());
			out.write("");
			out.close();
		
			BufferedReader br = null;
			if (HttpURLConnection.HTTP_OK == con.getResponseCode()) {
				br = new BufferedReader(new InputStreamReader(con.getInputStream()));
			}
			else if(HttpURLConnection.HTTP_GONE == con.getResponseCode()){
				// The heartbeat failed to renew (maybe due to network outage). We will re-register the subscription to service discovery.
				ServiceDiscovery sd = new ServiceDiscovery();
				sd.init();
				this.cancel();
			}
			else{
				br = new BufferedReader(new InputStreamReader(con.getErrorStream()));
			}
			
			StringBuffer response = new StringBuffer();
			String line;
		
			while ((line = br.readLine()) != null) {
				response.append(line);
			}
			br.close();
			
			System.out.println(con.getResponseCode() + " on heartbeat. " + response.toString());
		}
		catch(Exception e){
			e.printStackTrace();
		}
	}
}
