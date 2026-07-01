package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.Bucket;
import com.shaforostoff.dcimsort.data.MediaRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists MediaStore image buckets ("folders") with counts; returns the chosen one. */
public class FolderPickerActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ListView list;
    private TextView empty;
    private List<Bucket> buckets;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        list.setEmptyView(empty);

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (buckets == null || position >= buckets.size()) return;
            Bucket b = buckets.get(position);
            Intent result = new Intent();
            result.putExtra(Extras.RESULT_BUCKET_ID, b.id);
            result.putExtra(Extras.RESULT_REL_PATH, b.relativePath);
            result.putExtra(Extras.RESULT_DATA_DIR, b.dataDir);
            result.putExtra(Extras.RESULT_DISPLAY, b.displayName);
            result.putExtra(Extras.VOLUME_NAME, b.volumeName);
            setResult(RESULT_OK, result);
            finish();
        });

        load();
    }

    private static boolean isImageDirectory(Bucket b) {
        if (b.relativePath != null) {
            return b.relativePath.startsWith("DCIM/") || b.relativePath.startsWith("Pictures/");
        }
        if (b.dataDir != null) {
            String norm = b.dataDir.replace('\\', '/');
            return norm.contains("/DCIM/") || norm.endsWith("/DCIM")
                    || norm.contains("/Pictures/") || norm.endsWith("/Pictures");
        }
        return false;
    }

    private void load() {
        executor.execute(() -> {
            List<Bucket> all = new MediaRepository(this).listBuckets();
            List<Bucket> filtered = new ArrayList<>();
            for (Bucket b : all) {
                if (isImageDirectory(b)) filtered.add(b);
            }
            final List<Bucket> result = filtered;
            main.post(() -> {
                buckets = result;
                if (result.isEmpty()) {
                    empty.setText(R.string.no_photos);
                    return;
                }
                list.setAdapter(new ArrayAdapter<Bucket>(
                        this, android.R.layout.simple_list_item_2, android.R.id.text1, result) {
                    @Override
                    public View getView(int position, View convertView, android.view.ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        Bucket b = result.get(position);
                        TextView t1 = v.findViewById(android.R.id.text1);
                        TextView t2 = v.findViewById(android.R.id.text2);
                        String label = b.displayName;
                        if (b.volumeName != null && !"external_primary".equals(b.volumeName)) {
                            label = label + " (SD)";
                        }
                        t1.setText(label);
                        String path = b.relativePath != null ? b.relativePath
                                : (b.dataDir != null ? b.dataDir : "");
                        t2.setText(getString(R.string.photos_count_only, b.count) + "  ·  " + path);
                        return v;
                    }
                });
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
