package com.example.ciaobeeline.wear;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

public class MainActivity extends Activity {
    private static final String PATH = "/nav_update";

    private NavView navView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String lastJson = "";
    private int pollCount = 0;

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            pollCount++;
            loadLastDataLayerValue();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        navView = new NavView(this);
        setContentView(navView);

        Toast.makeText(this, "Ciao Beeline DEBUG avviato", Toast.LENGTH_SHORT).show();

        // Toccando lo schermo parte una demo locale.
        // Serve per verificare che la grafica del Carlyle funziona.
        navView.setOnClickListener(v -> {
            String demoJson = "{\"mode\":\"NAV\",\"speed\":36,\"dist\":300,\"turn\":\"RIGHT\",\"line\":\"120,200;120,165;145,140;145,95;105,60\"}";
            lastJson = demoJson;
            navView.update(demoJson);
            Toast.makeText(this, "Demo locale Carlyle OK", Toast.LENGTH_SHORT).show();
        });

        // All'avvio mostriamo una schermata di debug iniziale
        String startJson = "{\"mode\":\"DEBUG\",\"speed\":0,\"dist\":0,\"turn\":\"STRAIGHT\",\"line\":\"120,200;120,150;120,100;120,60\"}";
        navView.update(startJson);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Toast.makeText(this, "Polling Data Layer attivo", Toast.LENGTH_SHORT).show();

        handler.post(pollingRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();

        handler.removeCallbacks(pollingRunnable);
    }

    private void loadLastDataLayerValue() {
        Wearable.getDataClient(this).getDataItems()
                .addOnSuccessListener(items -> {
                    int totalItems = 0;
                    int matchingItems = 0;
                    String foundJson = null;

                    for (DataItem item : items) {
                        totalItems++;

                        String itemPath = item.getUri().getPath();

                        if (PATH.equals(itemPath)) {
                            matchingItems++;

                            foundJson = DataMapItem.fromDataItem(item)
                                    .getDataMap()
                                    .getString("json");
                        }
                    }

                    items.release();

                    if (foundJson != null) {
                        if (!foundJson.equals(lastJson)) {
                            lastJson = foundJson;
                            navView.update(foundJson);
                            Toast.makeText(
                                    this,
                                    "Ricevuto telefono OK",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    } else {
                        // Ogni tanto mostriamo un toast per capire che il polling sta girando.
                        if (pollCount % 5 == 0) {
                            Toast.makeText(
                                    this,
                                    "Polling... items " + totalItems + " nav " + matchingItems,
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
                })
                .addOnFailureListener(e ->
                        Toast.makeText(
                                this,
                                "Errore DataLayer: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show()
                );
    }
                                        }
