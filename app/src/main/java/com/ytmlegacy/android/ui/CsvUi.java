package com.ytmlegacy.android.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.OpenableColumns;

import com.ytmlegacy.android.R;
import com.ytmlegacy.android.data.Csv;
import com.ytmlegacy.android.data.Library;
import com.ytmlegacy.android.net.Http;
import com.ytmlegacy.android.yt.InnerTube;
import com.ytmlegacy.android.yt.Song;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Playlist import/export as CSV files. */
public final class CsvUi {

    static final int REQ_IMPORT = 11;
    static final int REQ_EXPORT = 12;
    private static final int MAX_ROWS = 2000;

    private final MainActivity act;
    private List<Song> pendingExport;
    private String pendingName = "";

    CsvUi(MainActivity act) {
        this.act = act;
    }

    // ------------------------------------------------------------------ picking

    public void pickImport() {
        if (Build.VERSION.SDK_INT >= 19) {
            try {
                act.startActivityForResult(Kitkat.openIntent(), REQ_IMPORT);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Intent get = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        if (get.resolveActivity(act.getPackageManager()) != null) {
            try {
                act.startActivityForResult(Intent.createChooser(get, act.getString(R.string.pick_file)), REQ_IMPORT);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        browseFiles();
    }

    @TargetApi(19)
    private static final class Kitkat {
        static Intent openIntent() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/comma-separated-values",
                    "text/plain", "application/csv", "application/vnd.ms-excel", "application/octet-stream"});
            return i;
        }

        static Intent createIntent(String name) {
            return new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/csv").putExtra(Intent.EXTRA_TITLE, name);
        }
    }

    /** Android 2.x without a file manager: list the CSV files on the storage. */
    private void browseFiles() {
        final List<File> found = new ArrayList<File>();
        File root = Environment.getExternalStorageDirectory();
        if (root != null) scan(root, 0, found);
        if (found.isEmpty()) {
            Ui.toast(act, R.string.no_csv_files);
            return;
        }
        String[] names = new String[found.size()];
        String rootPath = root.getAbsolutePath();
        for (int i = 0; i < names.length; i++) names[i] = found.get(i).getAbsolutePath().replace(rootPath, "");
        Ui.dialog(act).setTitle(R.string.pick_file).setItems(names, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                File f = found.get(which);
                importFrom(Uri.fromFile(f), f.getName());
            }
        }).show();
    }

    private static void scan(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null || out.size() > 200) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < 3 && !f.getName().startsWith(".") && !f.getName().equals("Android")) scan(f, depth + 1, out);
            } else if (f.getName().toLowerCase(java.util.Locale.US).endsWith(".csv")) {
                out.add(f);
            }
        }
    }

    void onResult(int request, int result, Intent data) {
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == REQ_IMPORT) {
            importFrom(uri, displayName(uri));
        } else if (request == REQ_EXPORT && pendingExport != null) {
            writeTo(uri, pendingExport);
            pendingExport = null;
        }
    }

    private String displayName(Uri uri) {
        String name = uri.getLastPathSegment();
        Cursor c = null;
        try {
            c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst() && c.getString(0) != null) name = c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return name == null ? "playlist" : name;
    }

    // ------------------------------------------------------------------ import

    @SuppressWarnings("deprecation")
    private void importFrom(final Uri uri, String fileName) {
        String base = fileName;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        final String name = dot > 0 ? base.substring(0, dot) : base;
        final ProgressDialog progress = new ProgressDialog(act);
        progress.setTitle(R.string.import_title);
        progress.setMessage(name);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setCancelable(true);
        progress.show();
        final Task<Object[]> task = new Task<Object[]>() {
            @Override
            protected Object[] work() throws Exception {
                InputStream in = act.getContentResolver().openInputStream(uri);
                if (in == null) throw new java.io.IOException("cannot open");
                byte[] data = Http.readAll(in, 20 * 1024 * 1024);
                String text = new String(data, "UTF-8");
                final List<Csv.Row> rows = Csv.rows(text);
                if (rows.size() > MAX_ROWS) rows.subList(MAX_ROWS, rows.size()).clear();
                if (rows.isEmpty()) return new Object[]{new ArrayList<Song>(), 0};
                Ui.post(new Runnable() {
                    @Override
                    public void run() {
                        progress.setMax(rows.size());
                    }
                });
                final AtomicInteger done = new AtomicInteger();
                ExecutorService pool = Executors.newFixedThreadPool(3);
                List<Future<Song>> futures = new ArrayList<Future<Song>>();
                for (final Csv.Row row : rows) {
                    futures.add(pool.submit(new java.util.concurrent.Callable<Song>() {
                        @Override
                        public Song call() {
                            try {
                                if (isCancelled()) return null;
                                return resolve(row);
                            } catch (Exception e) {
                                return null;
                            } finally {
                                final int n = done.incrementAndGet();
                                Ui.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        progress.setProgress(n);
                                    }
                                });
                            }
                        }
                    }));
                }
                List<Song> songs = new ArrayList<Song>();
                for (Future<Song> f : futures) {
                    Song s = f.get();
                    if (s != null) songs.add(s);
                }
                pool.shutdown();
                return new Object[]{songs, rows.size()};
            }

            @Override
            @SuppressWarnings("unchecked")
            protected void done(Object[] r) {
                dismiss(progress);
                List<Song> songs = (List<Song>) r[0];
                int total = (Integer) r[1];
                if (total == 0) {
                    Ui.toast(act, R.string.import_empty);
                    return;
                }
                if (songs.isEmpty()) {
                    Ui.toast(act, act.isOnline() ? R.string.import_empty : R.string.error_offline);
                    return;
                }
                Library lib = Library.get(act);
                long id = lib.createPlaylist(name);
                lib.addToPlaylist(id, songs);
                Ui.dialog(act).setMessage(act.getString(R.string.import_done, songs.size(), total, name))
                        .setPositiveButton(R.string.ok, null).show();
            }

            @Override
            protected void failed(Exception e) {
                dismiss(progress);
                Ui.toast(act, R.string.import_failed);
            }
        };
        progress.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                task.cancel();
            }
        });
        task.start();
    }

    private Song resolve(Csv.Row row) throws Exception {
        InnerTube tube = InnerTube.get(act);
        if (row.videoId.length() > 0) {
            if (row.title.length() > 0) {
                Song s = new Song(row.videoId, row.title, row.artist);
                s.album = row.album;
                s.durationSec = row.durationSec;
                s.thumb = "https://i.ytimg.com/vi/" + row.videoId + "/hqdefault.jpg";
                return s;
            }
            Song s = tube.videoDetails(row.videoId);
            if (s != null) return s;
            return null;
        }
        String q = row.artist.length() > 0 ? row.title + " " + row.artist : row.title;
        return Csv.bestMatch(row, tube.search(q, InnerTube.FILTER_SONGS));
    }

    // ------------------------------------------------------------------ export

    public void export(String name, List<Song> songs) {
        if (songs.isEmpty()) {
            Ui.toast(act, R.string.empty);
            return;
        }
        String file = name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".csv";
        if (Build.VERSION.SDK_INT >= 19) {
            pendingExport = songs;
            pendingName = file;
            try {
                act.startActivityForResult(Kitkat.createIntent(file), REQ_EXPORT);
                return;
            } catch (ActivityNotFoundException ignored) {
                pendingExport = null;
            }
        }
        // Android < 4.4: a folder on the memory card.
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "YTMLegacy");
            dir.mkdirs();
            File out = new File(dir, file);
            OutputStream o = new FileOutputStream(out);
            try {
                o.write(Csv.write(songs).getBytes("UTF-8"));
            } finally {
                o.close();
            }
            Ui.toast(act, act.getString(R.string.export_done, out.getAbsolutePath()));
        } catch (Exception e) {
            Ui.toast(act, R.string.export_failed);
        }
    }

    private void writeTo(Uri uri, List<Song> songs) {
        try {
            OutputStream o = act.getContentResolver().openOutputStream(uri);
            if (o == null) throw new java.io.IOException("cannot open");
            try {
                o.write(Csv.write(songs).getBytes("UTF-8"));
            } finally {
                o.close();
            }
            Ui.toast(act, act.getString(R.string.export_done, pendingName));
        } catch (Exception e) {
            Ui.toast(act, R.string.export_failed);
        }
    }

    private static void dismiss(ProgressDialog d) {
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Exception ignored) {
        }
    }
}
