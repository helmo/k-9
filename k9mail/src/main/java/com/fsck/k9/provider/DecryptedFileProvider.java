package com.fsck.k9.provider;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Locale;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.ParcelFileDescriptor;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.Log;

import com.fsck.k9.BuildConfig;
import com.fsck.k9.K9;
import com.fsck.k9.service.FileProviderInterface;
import org.apache.james.mime4j.codec.Base64InputStream;
import org.apache.james.mime4j.codec.QuotedPrintableInputStream;
import org.apache.james.mime4j.util.MimeUtil;
import org.openintents.openpgp.util.ParcelFileDescriptorUtil;


public class DecryptedFileProvider extends FileProvider {
    private static final String AUTHORITY = BuildConfig.APPLICATION_ID + ".decryptedfileprovider";
    private static final String DECRYPTED_CACHE_DIRECTORY = "decrypted";
    private static final long FILE_DELETE_THRESHOLD_MILLISECONDS = 3 * 60 * 1000;


    private static DecryptedFileProviderCleanupReceiver receiverRegistered = null;


    public static FileProviderInterface getFileProviderInterface(Context context) {
        final Context applicationContext = context.getApplicationContext();

        return new FileProviderInterface() {
            @Override
            public File createProvidedFile() throws IOException {
                registerFileCleanupReceiver(applicationContext);
                File decryptedTempDirectory = getDecryptedTempDirectory(applicationContext);
                return File.createTempFile("decrypted-", null, decryptedTempDirectory);
            }

            @Override
            public Uri getUriForProvidedFile(File file, @Nullable String encoding, String mimeType) throws IOException {
                Uri.Builder uriBuilder = FileProvider.getUriForFile(applicationContext, AUTHORITY, file).buildUpon();
                if (encoding != null) {
                    uriBuilder.appendQueryParameter("encoding", encoding);
                }
                uriBuilder.appendQueryParameter("mime_type", mimeType);
                return uriBuilder.build();
            }
        };
    }

    public static boolean deleteOldTemporaryFiles(Context context) {
        File tempDirectory = getDecryptedTempDirectory(context);
        boolean allFilesDeleted = true;
        long deletionThreshold = new Date().getTime() - FILE_DELETE_THRESHOLD_MILLISECONDS;
        for (File tempFile : tempDirectory.listFiles()) {
            long lastModified = tempFile.lastModified();
            if (lastModified < deletionThreshold) {
                boolean fileDeleted = tempFile.delete();
                if (!fileDeleted) {
                    Log.e(K9.LOG_TAG, "Failed to delete temporary file");
                    // TODO really do this? might cause our service to stay up indefinitely if a file can't be deleted
                    allFilesDeleted = false;
                }
            } else {
                if (K9.DEBUG) {
                    String timeLeftStr = String.format(
                            Locale.ENGLISH, "%.2f", (lastModified - deletionThreshold) / 1000 / 60.0);
                    Log.e(K9.LOG_TAG, "Not deleting temp file (for another " + timeLeftStr + " minutes)");
                }
                allFilesDeleted = false;
            }
        }

        return allFilesDeleted;
    }

    private static File getDecryptedTempDirectory(Context context) {
        File directory = new File(context.getCacheDir(), DECRYPTED_CACHE_DIRECTORY);
        if (!directory.exists()) {
            if (!directory.mkdir()) {
                Log.e(K9.LOG_TAG, "Error creating directory: " + directory.getAbsolutePath());
            }
        }

        return directory;
    }


    @Override
    public String getType(Uri uri) {
        return uri.getQueryParameter("mime_type");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        ParcelFileDescriptor pfd = super.openFile(uri, "r");

        InputStream decodedInputStream;
        String encoding = uri.getQueryParameter("encoding");
        if (MimeUtil.isBase64Encoding(encoding)) {
            InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            decodedInputStream = new Base64InputStream(inputStream);
        } else if (MimeUtil.isQuotedPrintableEncoded(encoding)) {
            InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            decodedInputStream = new QuotedPrintableInputStream(inputStream);
        } else { // no or unknown encoding
            if (K9.DEBUG && !TextUtils.isEmpty(encoding)) {
                Log.e(K9.LOG_TAG, "unsupported encoding, returning raw stream");
            }
            return pfd;
        }

        try {
            return ParcelFileDescriptorUtil.pipeFrom(decodedInputStream);
        } catch (IOException e) {
            // not strictly a FileNotFoundException, but failure to create a pipe is basically "can't access right now"
            throw new FileNotFoundException();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        if (level < TRIM_MEMORY_COMPLETE) {
            return;
        }
        final Context context = getContext();
        if (context == null) {
            return;
        }

        new AsyncTask<Void,Void,Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                deleteOldTemporaryFiles(context);
                return null;
            }
        }.execute();

        if (receiverRegistered != null) {
            context.unregisterReceiver(receiverRegistered);
            receiverRegistered = null;
        }
    }

    @MainThread // no need to synchronize for receiverRegistered
    private static void registerFileCleanupReceiver(Context context) {
        if (receiverRegistered != null) {
            return;
        }
        if (K9.DEBUG) {
            Log.d(K9.LOG_TAG, "Registering temp file cleanup receiver");
        }
        receiverRegistered = new DecryptedFileProviderCleanupReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        context.registerReceiver(receiverRegistered, intentFilter);
    }

    private static class DecryptedFileProviderCleanupReceiver extends BroadcastReceiver {
        @Override
        @MainThread
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                throw new IllegalArgumentException("onReceive called with action that isn't screen off!");
            }

            if (K9.DEBUG) {
                Log.d(K9.LOG_TAG, "Cleaning up temp files");
            }

            boolean allFilesDeleted = deleteOldTemporaryFiles(context);
            if (allFilesDeleted) {
                if (K9.DEBUG) {
                    Log.d(K9.LOG_TAG, "Unregistering temp file cleanup receiver");
                }
                context.unregisterReceiver(this);
                receiverRegistered = null;
            }
        }
    }
}
