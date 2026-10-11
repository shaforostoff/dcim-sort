package com.shaforostoff.dcimsort.work;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tiny append-only log of in-flight recompress operations, in app-private files, so a crash or
 * forced stop never costs a photo. Two kinds of entries:
 * <ul>
 *   <li>Insert (B/C/X): a new row was begun. If it was never completed, it is rolled back
 *   (deleted), leaving the untouched original. C is logged once the new file is published, before
 *   the original is deleted, so a crash in between leaves a duplicate rather than nothing.</li>
 *   <li>In-place (O/D): the original row is being overwritten. Its old bytes are gone the moment the
 *   write starts, so the new bytes wait in a redo file until D; an unfinished overwrite is handed
 *   back by {@link #reconcile} for {@link Mover#recover()} to finish.</li>
 * </ul>
 *
 * <p>The writer is opened once and kept for the run, flushed after every entry. Durability is the
 * same as reopening the file per line — the bytes are on their way to disk before the caller
 * proceeds — but a large job pays one open instead of two per photo, all of them serialized on
 * this object's lock across every worker thread.
 */
public class OrganizeJournal {
    private static final String REDO_PREFIX = "redo_";

    private final File dir;
    private final File file;
    private Writer writer;

    /** An in-place overwrite that was begun but never finished. */
    public static final class InPlace {
        public final long id;
        public final Uri uri;
        public final File redo;
        public final String rel, name, mime;

        InPlace(long id, Uri uri, File redo, String rel, String name, String mime) {
            this.id = id;
            this.uri = uri;
            this.redo = redo;
            this.rel = rel;
            this.name = name;
            this.mime = mime;
        }
    }

    public OrganizeJournal(Context ctx) {
        this.dir = ctx.getApplicationContext().getFilesDir();
        this.file = new File(dir, "organize_journal.tsv");
    }

    /** Where an in-place overwrite keeps its new bytes until done; outlives a crash, unlike cache. */
    public File redoFile(long id) {
        return new File(dir, REDO_PREFIX + id);
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

    /** About to overwrite {@code uri} from {@link #redoFile}, then rename it to rel/name/mime. */
    public synchronized void beginInPlace(long id, Uri uri, String rel, String name, String mime) {
        append("O\t" + id + "\t" + uri + "\t" + rel + "\t" + name + "\t" + mime + "\n");
    }

    /** The overwrite finished, or never touched the original; its redo file may go. */
    public synchronized void endInPlace(long id) {
        append("D\t" + id + "\n");
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
     * Deletes new URIs that were begun but never completed/aborted, clears the journal, and returns
     * the in-place overwrites still to finish. Call on service start, before any new work.
     */
    public synchronized List<InPlace> reconcile(ContentResolver resolver) {
        close();
        // Replayed in order: an id can be begun again after an abort, and only its latest begin
        // may still be open.
        Map<Long, String> inserts = new HashMap<>();
        Map<Long, String[]> inPlace = new HashMap<>();
        if (file.exists()) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split("\t");
                    try {
                        long id = Long.parseLong(parts[1]);
                        switch (parts[0]) {
                            case "B": inserts.put(id, parts[2]); break;
                            // C: the new file is published, so an in-place fallback is done too.
                            case "C": inserts.remove(id); inPlace.remove(id); break;
                            case "X": inserts.remove(id); break;
                            case "O": if (parts.length >= 6) inPlace.put(id, parts); break;
                            case "D": inPlace.remove(id); break;
                            default: break;
                        }
                    } catch (RuntimeException ignore) {
                        // Torn last line from a kill mid-append.
                    }
                }
            } catch (IOException ignore) {
            }
        }
        for (Map.Entry<Long, String> e : inserts.entrySet()) {
            Uri uri = Uri.parse(e.getValue());
            // Older versions journaled in-place overwrites as B: never delete the original itself.
            if (uri.equals(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, e.getKey()))) continue;
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignore) {
                // Already gone or no permission; nothing else we can safely do.
            }
        }
        List<InPlace> pending = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (Map.Entry<Long, String[]> e : inPlace.entrySet()) {
            String[] p = e.getValue();
            File redo = redoFile(e.getKey());
            if (!redo.exists()) continue;
            pending.add(new InPlace(e.getKey(), Uri.parse(p[2]), redo, p[3], p[4], p[5]));
            keep.add(redo.getName());
        }
        // Redo files of finished overwrites (killed between D and the delete).
        File[] leftovers = dir.listFiles((d, n) -> n.startsWith(REDO_PREFIX) && !keep.contains(n));
        if (leftovers != null) for (File f : leftovers) f.delete();
        file.delete();
        return pending;
    }
}
