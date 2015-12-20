package com.fsck.k9.view;


import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.LoaderManager;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Loader;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.fsck.k9.R;
import com.fsck.k9.activity.RecipientAdapter;
import com.fsck.k9.activity.RecipientLoader;
import com.fsck.k9.view.RecipientSelectView.Recipient;
import com.fsck.k9.mail.Address;
import com.tokenautocomplete.TokenCompleteTextView;


public class RecipientSelectView extends TokenCompleteTextView<Recipient> implements LoaderCallbacks<List<Recipient>> {

    public static final String ARG_QUERY = "query";

    private RecipientAdapter adapter;
    private String cryptoProvider;
    private LoaderManager loaderManager;

    public RecipientSelectView(Context context) {
        super(context);
        initView();
    }

    public RecipientSelectView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public RecipientSelectView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    private void initView() {
        // TODO: validator?

        // don't allow duplicates, based on equality of recipient objects, which is e-mail addresses
        allowDuplicates(false);

        // if a token is completed, pick an entry based on best guess.
        // Note that we override performCompletion, so this doesn't actually do anything
        performBestGuess(true);

        adapter = new RecipientAdapter(getContext());
        setAdapter(adapter);
    }

    @Override
    protected View getViewForObject(Recipient recipient) {
        LayoutInflater l = LayoutInflater.from(getContext());
        @SuppressLint("InflateParams") // root will be attached by caller
        View view = l.inflate(R.layout.recipient_token_item, null, false);

        // A name is preferred, but show if nothing is available
        TextView vName = (TextView) view.findViewById(android.R.id.text1);
        String personal = recipient.address.getPersonal();
        if ( ! TextUtils.isEmpty(personal)) {
            vName.setText(personal);
        } else {
            vName.setText(recipient.address.getAddress());
        }

        ImageView contactView = (ImageView) view.findViewById(R.id.contact_photo);
        RecipientAdapter.setContactPhotoOrPlaceholder(getContext(), contactView, recipient);

        View cryptoStatusRed = view.findViewById(R.id.contact_crypto_status_red);
        View cryptoStatusOrange = view.findViewById(R.id.contact_crypto_status_orange);
        View cryptoStatusGreen = view.findViewById(R.id.contact_crypto_status_green);

        boolean hasCryptoProvider = cryptoProvider != null;
        if (!hasCryptoProvider) {
            cryptoStatusRed.setVisibility(View.GONE);
            cryptoStatusOrange.setVisibility(View.GONE);
            cryptoStatusGreen.setVisibility(View.GONE);
        } else if (recipient.cryptoStatus == RecipientCryptoStatus.UNAVAILABLE) {
            cryptoStatusRed.setVisibility(View.VISIBLE);
            cryptoStatusOrange.setVisibility(View.GONE);
            cryptoStatusGreen.setVisibility(View.GONE);
        } else if (recipient.cryptoStatus == RecipientCryptoStatus.AVAILABLE_UNTRUSTED) {
            cryptoStatusRed.setVisibility(View.GONE);
            cryptoStatusOrange.setVisibility(View.VISIBLE);
            cryptoStatusGreen.setVisibility(View.GONE);
        } else if (recipient.cryptoStatus == RecipientCryptoStatus.AVAILABLE_TRUSTED) {
            cryptoStatusRed.setVisibility(View.GONE);
            cryptoStatusOrange.setVisibility(View.GONE);
            cryptoStatusGreen.setVisibility(View.VISIBLE);
        }

        return view;
    }

    @Override
    protected Recipient defaultObject(String completionText) {
        Address[] parsedAddresses = Address.parse(completionText);
        if (parsedAddresses.length == 0 || parsedAddresses[0].getAddress() == null) {
            return null;
        }
        return new Recipient(parsedAddresses[0]);
    }

    public boolean isEmpty() {
        return getObjects().isEmpty();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (getContext() instanceof Activity) {
            loaderManager = ((Activity) getContext()).getLoaderManager();
        }
    }

    @Override
    public void showDropDown() {
        boolean cursorIsValid = adapter != null;
        if (!cursorIsValid) {
            return;
        }
        super.showDropDown();
    }

    @Override
    public void onFocusChanged(boolean hasFocus, int direction, Rect previous) {
        super.onFocusChanged(hasFocus, direction, previous);

        if (hasFocus) {
            ((InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    @Override
    public void performCompletion() {
        if (getListSelection() == ListView.INVALID_POSITION && enoughToFilter()) {
            Object bestGuess;
            if (getAdapter().getCount() > 0) {
                bestGuess = getAdapter().getItem(0);
            } else {
                bestGuess = defaultObject(currentCompletionText());
            }
            if (bestGuess != null) {
                replaceText(convertSelectionToString(bestGuess));
            }
        } else {
            super.performCompletion();
        }
    }

    @Override
    protected void performFiltering(@NonNull CharSequence text, int start, int end, int keyCode) {
        String query = text.subSequence(start, end).toString();

        if (TextUtils.isEmpty(query) || query.length() < 2) {
            loaderManager.destroyLoader(0);
            return;
        }

        Bundle args = new Bundle();
        args.putString(ARG_QUERY, query);
        loaderManager.restartLoader(0, args, this);

    }

    public void setCryptoProvider(String cryptoProvider) {
        this.cryptoProvider = cryptoProvider;
    }

    public void addRecipients(Recipient... recipients) {
        for (Recipient recipient : recipients) {
            addObject(recipient);
        }
    }

    public Address[] getAddresses() {
        List<Recipient> recipients = getObjects();
        Address[] address = new Address[recipients.size()];
        for (int i = 0; i < address.length; i++) {
            address[i] = recipients.get(i).address;
        }
        return address;
    }

    @Override
    public Loader<List<Recipient>> onCreateLoader(int id, Bundle args) {
        String query = args != null && args.containsKey(ARG_QUERY) ? args.getString(ARG_QUERY) : "";
        // mAdapter.setSearchQuery(query);
        return new RecipientLoader(getContext(), cryptoProvider, query);
    }

    @Override
    public void onLoadFinished(Loader<List<Recipient>> loader, List<Recipient> data) {
        adapter.setRecipients(data);
    }

    @Override
    public void onLoaderReset(Loader<List<Recipient>> loader) {
        adapter.setRecipients(null);
    }

    public boolean hasUncompletedText() {
        return !TextUtils.isEmpty(currentCompletionText());
    }

    public enum RecipientCryptoStatus {
        UNDEFINED, UNAVAILABLE, AVAILABLE_UNTRUSTED, AVAILABLE_TRUSTED;

        public boolean isAvailable() {
            return this == AVAILABLE_TRUSTED || this == AVAILABLE_UNTRUSTED;
        }
    }

    public static class Recipient implements Serializable {
        @NonNull
        public final Address address;

        @Nullable // null means the address is not associated with a contact
        public final Long contactId;

        @Nullable // null if the contact has no photo. transient because we serialize this manually, see below.
        public transient Uri photoThumbnailUri;

        @NonNull
        private RecipientCryptoStatus cryptoStatus;

        public Recipient(@NonNull Address address) {
            this.address = address;
            this.contactId = null;
            this.cryptoStatus = RecipientCryptoStatus.UNDEFINED;
        }

        public Recipient(String name, String email, long contactId) {
            this.address = new Address(email, name);
            this.contactId = contactId;
            this.cryptoStatus = RecipientCryptoStatus.UNDEFINED;
        }

        @NonNull
        public RecipientCryptoStatus getCryptoStatus() {
            return cryptoStatus;
        }

        public void setCryptoStatus(@NonNull RecipientCryptoStatus cryptoStatus) {
            this.cryptoStatus = cryptoStatus;
        }

        @Override
        public boolean equals(Object o) {
            // Equality is entirely up to the address
            return o instanceof Recipient && address.equals(((Recipient) o).address);
        }

        private void writeObject(ObjectOutputStream oos) throws IOException {
            oos.defaultWriteObject();

            // custom serialization, Android's Uri class is not serializable
            if (photoThumbnailUri != null) {
                oos.writeInt(1);
                oos.writeUTF(photoThumbnailUri.toString());
            } else {
                oos.writeInt(0);
            }
        }

        private void readObject(ObjectInputStream ois) throws ClassNotFoundException, IOException {
            ois.defaultReadObject();

            // custom deserialization, Android's Uri class is not serializable
            if (ois.readInt() != 0) {
                String uriString = ois.readUTF();
                photoThumbnailUri = Uri.parse(uriString);
            }
        }

    }

}
