package com.danvelazco.fbwrapper;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.OnGestureListener;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.webkit.CookieSyncManager;
import android.webkit.GeolocationPermissions.Callback;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;


/**
 * Activity with a WebView wrapping facebook.com with its
 * own CookieSyncManager to hold cookies persistently.
 * 
 * @author Daniel Velazco
 *
 */
@SuppressLint({ "SetJavaScriptEnabled", "NewApi" })
public class FbWrapper extends Activity implements Constants, OnGestureListener {

	private ActionBar mActionBar;
	private long abLastShown;

	private WebView mFBWrapper;

	private GestureDetector mGestureScanner;
	
	@SuppressWarnings("deprecation")
	private android.text.ClipboardManager mAncientClipboard;
	
	private android.content.ClipboardManager mClipboard;

	private ValueCallback<Uri> mUploadMessage;
	private final static int RESULTCODE_PICUPLOAD = 1;

	private boolean mDesktopView = false;
	private String USERAGENT_ANDROID_DEFAULT;

	private ProgressBar mProgressBar;

	private boolean V = false;
	private boolean mHideAb = false;
	private boolean mAllowCheckins = false;
	private boolean mOpenLinksInside = false;
	private String mSiteMode;

	private SharedPreferences mSharedPrefs;

	/** Called when the activity is first created. */
	@Override
	@SuppressWarnings("deprecation")
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		/* Only mess with ActionBar if device is on honeycomb or higher */
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			mActionBar = getActionBar();
			mActionBar.setTitle(R.string.app_name_short);
			
			mClipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		} else {
			mAncientClipboard = (android.text.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
		}

		setContentView(R.layout.webview);

		mGestureScanner = new GestureDetector(this, this);

		/** Load shared preferences */
		mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this); 
		
		/** Hide ActionBar based on user's preferences */
		mHideAb = mSharedPrefs.getBoolean(PREFS_HIDE_AB, false);

		/** Logcat verbose, enable if we are in debug mode, otherwise get from preferences */
		V = BuildConfig.DEBUG ? true : mSharedPrefs.getBoolean(PREFS_LOGCAT_ENABLED, false);

		/** Whether the site should be loaded as the mobile or desktop version */
		mSiteMode = mSharedPrefs.getString(PREFS_SITE_MODE, PREFS_SITE_MODE_AUTO);

		/** Creates new CookieSyncManager instance that will manage cookies */
		CookieSyncManager.createInstance(this);

		/** Declare ProgressBar in order for it to be used later */
		mProgressBar = (ProgressBar)findViewById(R.id.progress_bar);

		/** Configure WebView */
		mFBWrapper = (WebView) findViewById(R.id.webview);
		mFBWrapper.setWebViewClient(new FbWebViewClient());
		mFBWrapper.setWebChromeClient(new FbWebChromeClient());
		mFBWrapper.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);

		mFBWrapper.setOnTouchListener(new OnTouchListener() {
			public boolean onTouch(View v, MotionEvent event) {
				if (mGestureScanner.onTouchEvent(event)) {
					return true;
				}
				return false;
			}
		});

		/** Apply settings for WebView */
		WebSettings webSettings = mFBWrapper.getSettings(); 
		webSettings.setJavaScriptEnabled(true);
		webSettings.setSavePassword(false);
		webSettings.setSaveFormData(true);
		webSettings.setSupportZoom(true);
		webSettings.setSaveFormData(false);
		webSettings.setSavePassword(false);
		
		mFBWrapper.clearFormData();

		/** Load default User Agent */
		USERAGENT_ANDROID_DEFAULT = webSettings.getUserAgentString();

		/** Allow us to load a custom URL */
		Intent intent = getIntent();
		Uri urlToLoad = null;

		/** Check to see if we receive Intent Data */
		if (intent.getData() != null) {
			urlToLoad = intent.getData();
		}

		/* Get a subject and text and check if this is a link trying to be shared */
		String sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
		String sharedUrl = intent.getStringExtra(Intent.EXTRA_TEXT);

		if (sharedUrl != null) {
			if (!sharedUrl.equals("")) {
				String formattedSharedUrl = String.format(URL_SHARE_LINKS, sharedUrl, sharedSubject);
				urlToLoad = Uri.parse(formattedSharedUrl);
			}
		}

		if (savedInstanceState != null) {
			/** Restore the state of the WebView using the saved instance state */
			mFBWrapper.restoreState(savedInstanceState);
		} else {
			/** Loads proper URL depending on device type */
			initSession(urlToLoad);
		}

	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {

		if (V) Log.i(LOG_TAG, "Saving instance state");

		/** Save the state of the WebView as a Bundle to the Instance State */
		mFBWrapper.saveState(outState);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig){

		if (V) Log.i(LOG_TAG, "Handling configuration change");

		/** Handle orientation configuration changes */
		super.onConfigurationChanged(newConfig);
	}

	@Override
	public void onResume() {
		super.onResume();

		/** Start synchronizing the CookieSyncManager */
		CookieSyncManager.getInstance().startSync();

		/** Re-load these preferences in case some of them were changed */
		mHideAb = mSharedPrefs.getBoolean(PREFS_HIDE_AB, false);
		V = BuildConfig.DEBUG ? true : mSharedPrefs.getBoolean(PREFS_LOGCAT_ENABLED, false);
		mAllowCheckins = mSharedPrefs.getBoolean(PREFS_ALLOW_CHECKINS, false);

		if (V) Log.i(LOG_TAG, "On resume");

		mOpenLinksInside = mSharedPrefs.getBoolean(PREFS_OPEN_LINKS_INSIDE, false);

		/** Check to see if the Site mode preference was just changed */
		if (!mSiteMode.equals(mSharedPrefs.getString(PREFS_SITE_MODE, PREFS_SITE_MODE_AUTO))) {

			/** Store the new changes on the global field */
			mSiteMode = mSharedPrefs.getString(PREFS_SITE_MODE, PREFS_SITE_MODE_AUTO);

			/** Loads proper URL depending on device type */
			initSession(null);
		}

	}

	@Override
	public void onPause() {
		super.onPause();

		if (V) Log.i(LOG_TAG, "On pause");

		/** Stop synchronizing the CookieSyncManager */
		CookieSyncManager.getInstance().stopSync();
	}

	@SuppressWarnings("deprecation")
	private void copyToClipboard(String text) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			ClipData clip = ClipData.newPlainText("label", text);
			mClipboard.setPrimaryClip(clip);
		} else {
			mAncientClipboard.setText(text);
		}
	}

	private void shareCurrentPage() {
		Intent i = new Intent(android.content.Intent.ACTION_SEND);
		i.setType("text/plain");
		i.putExtra(Intent.EXTRA_SUBJECT, "Facebook page");
		i.putExtra(Intent.EXTRA_TEXT, mFBWrapper.getUrl());
		startActivity(Intent.createChooser(i, "Share URL"));
	}

	private void destroyWebView() {

		if (V) Log.w(LOG_TAG, "Destroying web view...");

		/** Avoid an NPE */
		if (mFBWrapper != null) {

			/** Free memory and destroy WebView*/
			mFBWrapper.freeMemory();
			mFBWrapper.destroy();
			mFBWrapper = null;
		}

		/** Avoid an NPE */
		if (mSharedPrefs != null) {

			/** Clean up shared preferences */
			mSharedPrefs = null;
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode,
			Intent intent) {
		if (requestCode == RESULTCODE_PICUPLOAD) {

			if (null == mUploadMessage) return;

			Uri result = intent == null || resultCode != RESULT_OK ? null : intent.getData();
			mUploadMessage.onReceiveValue(result);
			mUploadMessage = null;

		}
	}

	private class FbWebChromeClient extends WebChromeClient {
		@Override
		public void onProgressChanged(WebView view, int progress) {

			/** Posts current progress to the ProgressBar */
			mProgressBar.setProgress(progress);
		}

		@Override
		public void onGeolocationPermissionsShowPrompt(String origin, Callback callback) {
			super.onGeolocationPermissionsShowPrompt(origin, callback);

			if (V) Log.w(LOG_TAG, "Web site is trying to access geoloction");

			if (!mAllowCheckins) {
				showCheckinsDisabledAlert();
			}

			callback.invoke(origin, mAllowCheckins, false);
		}

		public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {  
			mUploadMessage = uploadMsg;  
			Intent i = new Intent(Intent.ACTION_GET_CONTENT);  
			i.addCategory(Intent.CATEGORY_OPENABLE);  
			i.setType("image/*");  
			FbWrapper.this.startActivityForResult(Intent.createChooser(i, "File Chooser" ), FbWrapper.RESULTCODE_PICUPLOAD);  
		}

		@SuppressWarnings("unused")
		public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType) {  
			openFileChooser(uploadMsg, "", "");  
		}

		@SuppressWarnings("unused")
		public void openFileChooser(ValueCallback<Uri> uploadMsg) {
			openFileChooser(uploadMsg, "", "");
		}

	}

	private class FbWebViewClient extends WebViewClient {

		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {

			/** Avoid NPEs when clicking on some weird links on facebook.com */
			if (url.equals("about:blank")) return false;

			if (!mOpenLinksInside) {
				if (url.startsWith("https://m.facebook.com/l.php") ||  
						url.startsWith("http://m.facebook.com/l.php") ||
						url.startsWith("https://www.facebook.com/l.php") ||
						url.startsWith("http://www.facebook.com/l.php") ||
						url.startsWith("https://facebook.com/l.php") ||
						url.startsWith("http://facebook.com/l.php")) {
					Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
					startActivity(intent);
					return true;
				}
			}

			/** Get the URL's domain name */
			String domain = Uri.parse(url).getHost();

			/** Avoid an NPE */
			if (domain != null) {

				/** Output URL */
				if (V) Log.d(LOG_TAG, "Loading URL: " + url);

				/** Let this WebView load the page. */
				if (domain.equals("m.facebook.com")) {
					return false;
				} else if (domain.equals("facebook.com")) {
					return false;
				} else if (domain.equals("www.facebook.com")) {
					return false;
				}

			}

			/** This link is not for a page on my site, launch another Activity that handles URLs */
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
			startActivity(intent);
			return true;
		}

		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon) {
			super.onPageStarted(view, url, favicon);

			if (V) Log.i(LOG_TAG, "Started loading a new page: " + url);

			if (mActionBar != null) {
				mActionBar.show();
			}

			/** We just started loading new content, show ProgressBar */
			mProgressBar.setVisibility(View.VISIBLE);
		}

		@Override
		public void onPageFinished (WebView view, String url) {
			super.onPageFinished(view, url);

			if (V) Log.i(LOG_TAG, "Finished loading the new page: " + url);

			if (mActionBar != null) {
				if (mHideAb) mActionBar.hide();
			}

			/** We just finished loading the new content, hide ProgressBar */
			mProgressBar.setVisibility(View.INVISIBLE);

			/** Tell the CookieSyncManager to synchronize */
			CookieSyncManager.getInstance().sync();
		}

	}

	/**
	 * Sets the user agent to the default user agent
	 * and load the mobile site.
	 */
	private void setDefaultUserAgent(Uri urlToLoad) {

		if (V) Log.d(LOG_TAG, "Initialize default user-agent: " + USERAGENT_ANDROID_DEFAULT);

		mDesktopView = false;
		mFBWrapper.getSettings().setUserAgentString(USERAGENT_ANDROID_DEFAULT);

		if (urlToLoad != null)
			mFBWrapper.loadUrl(urlToLoad.toString());
		else
			mFBWrapper.loadUrl(URL_MOBILE_SITE);
	}

	/**
	 * Sets the user agent to the default user agent
	 * and load the mobile site.
	 */
	private void setMobileUserAgent(Uri urlToLoad) {

		if (V) Log.d(LOG_TAG, "Initialize for mobile " + USER_AGENT_MOBILE);

		mDesktopView = false;
		mFBWrapper.getSettings().setUserAgentString(USER_AGENT_MOBILE);

		if (urlToLoad != null)
			mFBWrapper.loadUrl(urlToLoad.toString());
		else
			mFBWrapper.loadUrl(URL_MOBILE_SITE);
	}

	/**
	 * Sets the user agent to the desktop one
	 * and load the desktop site
	 */
	private void setDesktopUserAgent(Uri urlToLoad) {

		if (V) Log.d(LOG_TAG, "Initialize for desktop: " + USER_AGENT_DESKTOP);

		mDesktopView = true;
		mFBWrapper.getSettings().setUserAgentString(USER_AGENT_DESKTOP);

		if (urlToLoad != null)
			mFBWrapper.loadUrl(urlToLoad.toString());
		else
			mFBWrapper.loadUrl(URL_DESKTOP_SITE);
	}

	private void setupConfigForTablets() {
		mFBWrapper.getSettings().setBuiltInZoomControls(true);
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
			mFBWrapper.getSettings().setDisplayZoomControls(false);
	}

	private void setupConfigForPhones() {
		mFBWrapper.getSettings().setBuiltInZoomControls(false);
		
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
			mFBWrapper.getSettings().setDisplayZoomControls(false);
	}	


	/**
	 * Determines whether to load the mobile or desktop version
	 * depending on screen configuration. 
	 */
	private void initSession(Uri urlToLoad) {

		/** Automatically check the proper site to load depending on screen size */
		if (mSiteMode.equals(PREFS_SITE_MODE_AUTO)) {

			/** ICS allows phones AND tablets */
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {

				Configuration config = getResources().getConfiguration();
				if (config.smallestScreenWidthDp >= 600) {
					/** For tablets */
					setupConfigForTablets();
					setDesktopUserAgent(urlToLoad);
				} else {
					/** For phones */
					setupConfigForPhones();
					setDefaultUserAgent(urlToLoad);
				}

				/** Honeycomb only allowed tablets, always assume it's a tablet */
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
					&& Build.VERSION.SDK_INT <= Build.VERSION_CODES.HONEYCOMB_MR2) {
				setupConfigForTablets();
				setDesktopUserAgent(urlToLoad);

				/** There were no tablets before Honeycomb, assume it's a phone */
			} else {
				setupConfigForPhones();
				setDefaultUserAgent(urlToLoad);
			}

			/** Force the desktop version to load */
		} else if (mSiteMode.equals(PREFS_SITE_MODE_DESKTOP)) {
			setupConfigForTablets();
			setDesktopUserAgent(urlToLoad);

			/** Force the mobile version to load */
		} else if (mSiteMode.equals(PREFS_SITE_MODE_MOBILE)) {
			setupConfigForPhones();
			setMobileUserAgent(urlToLoad);

			/** Otherwise force the mobile version to load */
		} else {
			setupConfigForPhones();
			setDefaultUserAgent(urlToLoad);
		}

	}

	/**
	 * Load the notification page view via the menu button
	 */
	private void loadNotificationsView() {

		if (!mDesktopView)
			mFBWrapper.loadUrl(URL_MOBILE_NOTIFICATIONS);
		else
			mFBWrapper.loadUrl(URL_DESKTOP_NOTIFICATIONS);
	}

	private void webViewJumpTop() {
		if (V) Log.i(LOG_TAG, "Jump to the top!");
		mFBWrapper.loadUrl("javascript:window.scrollTo(0,0);");
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {

		/** Check if the key event was the BACK key and if there's history */
		if ((keyCode == KeyEvent.KEYCODE_BACK) && mFBWrapper.canGoBack()) {
			if (V) Log.i(LOG_TAG, "User pressed back button");
			mFBWrapper.goBack();
			return true;
		}

		return super.onKeyDown(keyCode, event);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);
		return true;
	}

	private void showCheckinsDisabledAlert() {
		AlertDialog alertDialog = new AlertDialog.Builder(this).create();
		alertDialog.setTitle(getString(R.string.lbl_dialog_alert));
		alertDialog.setMessage(getString(R.string.txt_checkins_disables)); 
		alertDialog.setButton(DialogInterface.BUTTON_NEUTRAL, getString(R.string.lbl_dialog_ok), 
				new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				return;
			} 
		});
		alertDialog.show();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {

		switch (item.getItemId()) {
		case R.id.menu_jump_top:
			webViewJumpTop();
			return true;
		case R.id.menu_news_feed:
			initSession(null);
			return true;
		case R.id.menu_copy_url:
			copyToClipboard(mFBWrapper.getUrl());
			return true;
		case R.id.menu_share:
			shareCurrentPage();
			return true;
		case R.id.menu_refresh:
			mFBWrapper.reload();
			return true;
		case R.id.menu_notifications:
			loadNotificationsView();
			return true;
		case R.id.menu_preferences:
			startActivity(new Intent(this, Preferences.class));
			return true;
		case R.id.menu_exit:
			destroyWebView();
			finish();
			return true;
		}

		return false;
	}

	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {

		if (mActionBar == null) {
			return false;
		}

		/* Only show action bar if user flings down very fast */
		if (e1.getRawY() < e2.getRawY() && velocityY > ACTION_BAR_SHOW_FLING_SPEED) {
			if (V) Log.i(LOG_TAG, "Fast fling down");
			if (!mActionBar.isShowing()) {
				mActionBar.show();
				abLastShown = System.currentTimeMillis();
				return true;
			}
		}	

		return false;
	}

	public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
			float distanceY) {

		if (mActionBar == null) {
			return false;
		}

		if (e1.getRawY() > e2.getRawY()) {
			if (V) Log.i(LOG_TAG, "Scrolling up");
			/* Only hide the bar if the last time we showed it is over 5 seconds ago */
			if (mHideAb && (System.currentTimeMillis()-abLastShown) > ACTION_BAR_HIDE_TIMEOUT) {
				mActionBar.hide();
			}
		}

		return false;
	}

	public boolean onDown(MotionEvent arg0) {
		return false;
	}

	public boolean onSingleTapUp(MotionEvent e) {
		return false;
	}

	public void onLongPress(MotionEvent e) {}

	public void onShowPress(MotionEvent e) {}

}
