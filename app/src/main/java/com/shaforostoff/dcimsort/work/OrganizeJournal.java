package com.shaforostoff.dcimsort.work;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tiny append-only log of in-flight recompress operations, in app-private files. Lets a crash or
 * forced stop be reconciled so we never leave both a newly written file and its original: any
 * "begun but not completed" new file is rolled back (deleted), leaving the untouched original.
 *
 * <p>The writer is opened once and kept for the run, flushed after every entry. Durability is the
 * same as reopening the file per line — the bytes are on their way to disk before the caller
 * proceeds — but a large job pays one open instead of two per photo, all of them serialized on
 * this object's lock across every worker thread.
 */
public class OrganizeJournal {
    private final File file;
    private Writer writer;

    public OrganizeJournal(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), "organize_journal.tsv");
    }

    public synchronized void begin(long id, Uri newUri) {
        append("B\t" + id + "\t" + newUri + "\n");
    }

    public synchronized void complete(long id) {
        append("C\t" + id + "\n");
    }

    /** New file already rolled back by caller; mark resolved. */
    public synchronized void abort(long id) {
        append("X\t" + id + "\n");
    }

    /** Releases the open writer. Safe to call more than once; appending again reopens it. */
    public synchronized void close() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignore) {
        } finally {
            writer = null;
        }
    }

    private void append(String line) {
        try {
            if (writer == null) writer = new BufferedWriter(new FileWriter(file, true));
            writer.write(line);
            writer.flush(); // the entry has to survive a kill before the caller acts on it
        } catch (IOException ignore) {
            close(); // drop the broken writer so the next append can retry from scratch
        }
    }

    /**
     * Deletes any new URIs that were begun but never completed/aborted, then clears the journal.
     * Call on service start.
     */
    public synchronized void reconcile(ContentResolver resolver) {
        close();
        if (!file.exists()) return;
        List<String[]> begins = new ArrayList<>();
        Set<Long> resolved = new HashSet<>();
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length == 0) continue;
                if ("B".equals(parts[0]) && parts.length >= 3) {
                    begins.add(new String[]{parts[1], parts[2]});
                } else if (("C".equals(parts[0]) || "X".equals(parts[0])) && parts.length >= 2) {
                    try { resolved.add(Long.parseLong(parts[1])); } catch (NumberFormatException ignore) {}
                }
            }
        } catch (IOException ignore) {
        }
        for (String[] b : begins) {
            long id;
            try { id = Long.parseLong(b[0]); } catch (NumberFormatException e) { continue; }
            if (resolved.contains(id)) continue;
            try {
                resolver.delete(Uri.parse(b[1]), null, null);
            } catch (Exception ignore) {
                // Already gone or no permission; nothing else we can safely do.
            }
        }
        file.delete();
    }
}
