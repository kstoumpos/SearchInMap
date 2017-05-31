package com.couchbase.grocerysync;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

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
import com.google.gson.Gson;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MainActivity extends Activity implements Replication.ChangeListener,
        OnItemClickListener, OnItemLongClickListener{

    public static String TAG = "POI";

    //constants
    public static final String DATABASE_NAME = "myapp";
    public static final String designDocName = "grocery-local";
    public static final String byDateViewName = "byDate";
    public static final String parseDocPoi = "PARSE_DOC_POI";

    // By default, use the sync gateway running on the Couchbase server.
    public static final String SYNC_URL = "http://81.171.24.208:4984/myapp/";

    //main screen
    protected EditText addItemEditText;
    protected ListView itemListView;
    protected PoiArrayAdapter poiArrayAdapter;

    //couch internals
    protected static Manager manager;
    private Database database;
    private LiveQuery liveQuery;
    String JSONString;


    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        //connect items from layout
        addItemEditText = (EditText)findViewById(R.id.addItemEditText);
        itemListView = (ListView)findViewById(R.id.itemListView);

        try {
            startCBLite();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error Initializing CBLIte, see logs for details", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error initializing CBLite", e);
        }

    }


    protected void onDestroy() {
        if(manager != null) {
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
        }catch ( CouchbaseLiteException e) {
            result=null;
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
                            for (Iterator<QueryRow> it = event.getRows(); it.hasNext();) {
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
        itemListView.setAdapter(poiArrayAdapter);
        itemListView.setOnItemClickListener(MainActivity.this);
        itemListView.setOnItemLongClickListener(MainActivity.this);
    }


    private Poi parseDocPoi(Document d ) {

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

        Log.i(parseDocPoi + " title: ",""+ Title);
        Log.i(parseDocPoi + " Category: ",""+ Category);
        Log.i(parseDocPoi + " Longitude: ",""+ Longitude);
        Log.i(parseDocPoi + " Latitude: ",""+ Latitude);
        Log.i(parseDocPoi + " Order: ",""+ Order);
        //Log.i(parseDocPoi + " mLatitude: ",""+ mLatitude.toString());
        //Log.i(parseDocPoi + " mLongitude: ",""+ mLongitude.toString());



        Poi mPoi = new Poi(Title, Latitude, Longitude, Category, Order);
        //String JSONString = gson.toJson(poiObj, Poi.class); //Convert the object to json string using Gson
        //Poi poi = (Poi) poiObj;
        gson.fromJson(JSONString, Poi.class); //convert the json string to Poi object
        //Log.i(parseDocPoi + " - JSONString:", JSONString); //empty!!!
        Log.i(parseDocPoi + " getPoiFromDocument ", "jsonString>>>" + mPoi.getCategory()); //Marker Category
        return mPoi;
    }

    /**
     * Handle click on item in list
     */
    public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {

        QueryRow row = (QueryRow) adapterView.getItemAtPosition(position);
        Document document = row.getDocument();
        Map<String, Object> newProperties = new HashMap<String, Object>(document.getProperties());

        boolean checked = ((Boolean) newProperties.get("check")).booleanValue();
        newProperties.put("check", !checked);

        try {
            document.putProperties(newProperties);
            poiArrayAdapter.notifyDataSetChanged();
        } catch (Exception e) {
            Toast.makeText(getApplicationContext(), "Error updating database, see logs for details", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error updating database", e);
        }

    }

    /**
     * Handle long-click on item in list
     */
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long id) {

        QueryRow row = (QueryRow) adapterView.getItemAtPosition(position);
        final Document clickedDocument = row.getDocument();
        String itemText = (String) clickedDocument.getCurrentRevision().getProperty("text");

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        AlertDialog alert = builder.setTitle("Delete Item?")
                .setMessage("Are you sure you want to delete \"" + itemText + "\"?")
                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        try {
                            clickedDocument.delete();
                        } catch (Exception e) {
                            Toast.makeText(getApplicationContext(), "Error deleting document, see logs for details", Toast.LENGTH_LONG).show();
                            Log.e(TAG, "Error deleting document", e);
                        }
                    }
                })
                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        // Handle Cancel
                    }
                })
                .create();

        alert.show();

        return true;
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
        }
        else {
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

}
