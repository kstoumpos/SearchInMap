package com.couchbase.grocerysync;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Toast;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.DatabaseOptions;
import com.couchbase.lite.Document;
import com.couchbase.lite.Emitter;
import com.couchbase.lite.LiveQuery;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.Query;
import com.couchbase.lite.QueryEnumerator;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.android.AndroidContext;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.util.Log;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.places.PlaceLikelihood;
import com.google.android.gms.location.places.PlaceLikelihoodBuffer;
import com.google.android.gms.location.places.Places;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.gson.Gson;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

public class MapsActivity extends FragmentActivity implements Replication.ChangeListener, OnMapReadyCallback, LocationListener {

    public static String TAG = "POI";
    private GoogleMap mMap;
    private CameraPosition mCameraPosition;


    // Keys for storing activity state.
    private static final String KEY_CAMERA_POSITION = "camera_position";
    private static final String KEY_LOCATION = "location";

    //constants
    public static final String DATABASE_NAME = "myapp";
    public static final String designDocName = "grocery-local";
    public static final String byDateViewName = "byDate";
    public static final String parseDocPoi = "PARSE_DOC_POI";

    // By default, use the sync gateway running on the Couchbase server.
    public static final String SYNC_URL = "http://81.171.24.208:4984/myapp/";

    protected PoiArrayAdapter poiArrayAdapter;

    //couch internals
    protected static Manager manager;
    private Database database;
    private LiveQuery liveQuery;
    String JSONString;

    // Used for selecting the current place.
    private final int mMaxEntries = 5;
    private String[] mLikelyPlaceNames = new String[mMaxEntries];
    private String[] mLikelyPlaceAddresses = new String[mMaxEntries];
    private String[] mLikelyPlaceAttributions = new String[mMaxEntries];
    private LatLng[] mLikelyPlaceLatLngs = new LatLng[mMaxEntries];

    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            startCBLite();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error Initializing CBLIte, see logs for details", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error initializing CBLite", e);
        }

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    protected void onDestroy() {
        if (manager != null) {
            manager.close();
        }
        super.onDestroy();
    }

    protected void startCBLite() throws Exception {

        Manager.enableLogging(TAG, Log.VERBOSE);
        Manager.enableLogging(Log.TAG, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_SYNC_ASYNC_TASK, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_SYNC, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_QUERY, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_VIEW, Log.VERBOSE);
        Manager.enableLogging(Log.TAG_DATABASE, Log.VERBOSE);

        manager = new Manager(new AndroidContext(getApplicationContext()), Manager.DEFAULT_OPTIONS);

        //install a view definition needed by the application
        DatabaseOptions options = new DatabaseOptions();
        options.setCreate(true);
        database = manager.openDatabase(DATABASE_NAME, options);
        com.couchbase.lite.View viewItemsByDate = database.getView(String.format("%s/%s", designDocName, byDateViewName));
        viewItemsByDate.setMap(new Mapper() {
            @Override
            public void map(Map<String, Object> document, Emitter emitter) {
                Object createdAt = document.get("created_at");
                if (createdAt != null) {
                    emitter.emit(createdAt.toString(), null);
                }
            }
        }, "1.0");

        initItemListAdapter();

        startLiveQuery(viewItemsByDate);

        startSync();

        loadPois();

    }

    public void loadPois() {

        QueryEnumerator result;

        // Let's find the documents that are public so we can get them:
        Query query = database.createAllDocumentsQuery();
        query.setAllDocsMode(Query.AllDocsMode.ALL_DOCS);
        try {
            result = query.run();
        } catch (CouchbaseLiteException e) {
            result = null;
            Log.w("MYAPP", "errrrrrrrrrrrrrrrrrrrrrorrrrrrrrrrrrrrrr");
        }
        for (Iterator<QueryRow> it = result; it.hasNext(); ) {
            QueryRow row = it.next();

            Log.w("MYAPP", "Poi in document with id: %s", row.getDocumentId());
            Document document = database.getDocument(row.getDocumentId());
            parseDocPoi(document);
        }
    }

    private void startSync() {

        URL syncUrl;
        try {
            syncUrl = new URL(SYNC_URL);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        Replication pullReplication = database.createPullReplication(syncUrl);
        pullReplication.setContinuous(true);

        Replication pushReplication = database.createPushReplication(syncUrl);
        pushReplication.setContinuous(true);

        pullReplication.start();
        pushReplication.start();

        pullReplication.addChangeListener(this);
        pushReplication.addChangeListener(this);

    }

    private void startLiveQuery(com.couchbase.lite.View view) throws Exception {

        if (liveQuery == null) {

            liveQuery = view.createQuery().toLiveQuery();

            liveQuery.addChangeListener(new LiveQuery.ChangeListener() {
                public void changed(final LiveQuery.ChangeEvent event) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            poiArrayAdapter.clear();
                            for (Iterator<QueryRow> it = event.getRows(); it.hasNext(); ) {
                                poiArrayAdapter.add(it.next());
                            }
                            poiArrayAdapter.notifyDataSetChanged();
                        }
                    });
                }
            });

            liveQuery.start();

        }

    }

    private void initItemListAdapter() {
        poiArrayAdapter = new PoiArrayAdapter(
                getApplicationContext(),
                R.layout.grocery_list_item,
                R.id.label,
                new ArrayList<QueryRow>()
        );
    }


    private Poi parseDocPoi(Document d) {

        Gson gson = new Gson();
        Map<String, Object> properties = d.getProperties();

        String Title = (String) properties.get("title");
        String Category = (String) properties.get("category");
        int Order = (int) properties.get("order");
        Double Latitude = (Double) properties.get("latitude");
        Double Longitude = (Double) properties.get("longitude");

        //Double mLatitude = Double.valueOf(Latitude.trim()).doubleValue();
        //Double.valueOf(s.trim()).doubleValue();
        //Double mLongitude = Double.parseDouble(Longitude);
        //Double mLatitude = 120.00;

        Log.i(parseDocPoi + " title: ", "" + Title);
        Log.i(parseDocPoi + " Category: ", "" + Category);
        Log.i(parseDocPoi + " Longitude: ", "" + Longitude);
        Log.i(parseDocPoi + " Latitude: ", "" + Latitude);
        Log.i(parseDocPoi + " Order: ", "" + Order);

        Poi mPoi = new Poi(Title, Latitude, Longitude, Category, Order);
        //String JSONString = gson.toJson(poiObj, Poi.class); //Convert the object to json string using Gson
        //Poi poi = (Poi) poiObj;
        gson.fromJson(JSONString, Poi.class); //convert the json string to Poi object
        Log.i(parseDocPoi + " getPoiFromDocument ", "jsonString>>>" + mPoi.getCategory()); //Marker Category
        return mPoi;
    }

    /**
     * Add settings item to the menu
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, 0, 0, "Settings");
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Launch the settings activity
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 0:
                startActivity(new Intent(this, PoiSyncPreferencesActivity.class));
                return true;
        }
        return false;
    }

    @Override
    public void changed(Replication.ChangeEvent event) {

        Replication replication = event.getSource();
        Log.d(TAG, "Replication : " + replication + " changed.");
        if (!replication.isRunning()) {
            String msg = String.format("Replicator %s not running", replication);
            Log.d(TAG, msg);
        } else {
            int processed = replication.getCompletedChangesCount();
            int total = replication.getChangesCount();
            String msg = String.format("Replicator processed %d / %d", processed, total);
            Log.d(TAG, msg);
        }

        if (event.getError() != null) {
            showError("Sync error", event.getError());
        }

    }

    public void showError(final String errorMessage, final Throwable throwable) {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String msg = String.format("%s: %s", errorMessage, throwable);
                Log.e(TAG, msg, throwable);
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });

    }

    @Override
    public void onLocationChanged(Location location) {
        Double lat = (Double) (location.getLatitude());
        Double lng = (Double) (location.getLongitude());
    }


    @Override
    public void onMapReady(GoogleMap googleMap) {

        mMap = googleMap;

        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {

                                      @Override
                                      // Return null here, so that getInfoContents() is called next.
                                      public View getInfoWindow(Marker arg0) {
                                          return null;
                                      }

                                      @Override
                                      public View getInfoContents(Marker marker) {
                                          // Inflate the layouts for the info window, title and snippet.
                                          View infoWindow = getLayoutInflater().inflate(R.layout.custom_info_contents,
                                                  (FrameLayout)findViewById(R.id.map), false);

//                                          TextView title = ((TextView) infoWindow.findViewById(R.id.title));
//                                          title.setText(marker.getTitle());
//
//                                          TextView snippet = ((TextView) infoWindow.findViewById(R.id.snippet));
//                                          snippet.setText(marker.getSnippet());

                                          return infoWindow;
                                      }
                                  });

        Query query = database.createAllDocumentsQuery();
        QueryEnumerator result;

        query.setAllDocsMode(Query.AllDocsMode.ALL_DOCS);
        try {
            result = query.run();
        } catch (CouchbaseLiteException e) {
            result = null;
            Log.w("MYAPP", "errrrrrrrrrrrrrrrrrrrrrorrrrrrrrrrrrrrrr");
        }
        for (Iterator<QueryRow> it = result; it.hasNext(); ) {
            QueryRow row = it.next();

            Log.w("MYAPP", "Poi in document with id: %s", row.getDocumentId());
            Document document = database.getDocument(row.getDocumentId());
            String Title = (String) document.getProperties().get("title");
            Double Latitude = (Double) document.getProperties().get("latitude");
            Double Longitude = (Double) document.getProperties().get("longitude");
            String Category = (String) document.getProperties().get("category");

            LatLng marker = new LatLng(Latitude, Longitude);
            mMap.addMarker(new MarkerOptions().position(marker).title(Title).snippet(Category));
            //mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(lat, location.getLongitude()), 12.0f));
            // Add a marker in Sydney, Australia, and move the camera.
            //LatLng marker = new LatLng(-34, 151);

            Log.i("Status: ", "End the App!");
        }
    }
}
