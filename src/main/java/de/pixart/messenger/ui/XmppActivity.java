package de.pixart.messenger.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import com.vanniktech.emoji.EmojiManager;
import com.vanniktech.emoji.ios.IosEmojiProvider;

import net.java.otr4j.session.SessionID;

import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import de.pixart.messenger.Config;
import de.pixart.messenger.R;
import de.pixart.messenger.crypto.PgpEngine;
import de.pixart.messenger.entities.Account;
import de.pixart.messenger.entities.Contact;
import de.pixart.messenger.entities.Conversation;
import de.pixart.messenger.entities.Message;
import de.pixart.messenger.entities.MucOptions;
import de.pixart.messenger.entities.Presences;
import de.pixart.messenger.http.HttpConnectionManager;
import de.pixart.messenger.services.AvatarService;
import de.pixart.messenger.services.BarcodeProvider;
import de.pixart.messenger.services.UpdateService;
import de.pixart.messenger.services.XmppConnectionService;
import de.pixart.messenger.services.XmppConnectionService.XmppConnectionBinder;
import de.pixart.messenger.utils.CryptoHelper;
import de.pixart.messenger.utils.ExceptionHelper;
import de.pixart.messenger.utils.UIHelper;
import de.pixart.messenger.xmpp.OnKeyStatusUpdated;
import de.pixart.messenger.xmpp.OnUpdateBlocklist;
import de.pixart.messenger.xmpp.jid.InvalidJidException;
import de.pixart.messenger.xmpp.jid.Jid;

public abstract class XmppActivity extends Activity {

    protected static final int REQUEST_ANNOUNCE_PGP = 0x0101;
    protected static final int REQUEST_INVITE_TO_CONVERSATION = 0x0102;
    protected static final int REQUEST_CHOOSE_PGP_ID = 0x0103;
    protected static final int REQUEST_BATTERY_OP = 0x13849ff;

    public static final String EXTRA_ACCOUNT = "account";

    public XmppConnectionService xmppConnectionService;
    public HttpConnectionManager mHttpConnectionManager;
    public boolean xmppConnectionServiceBound = false;
    protected boolean registeredListeners = false;

    protected int mPrimaryTextColor;
    protected int mSecondaryTextColor;
    protected int mTertiaryTextColor;
    protected int mPrimaryBackgroundColor;
    protected int mSecondaryBackgroundColor;
    protected int mColorRed;
    protected int mColorWarningButton;
    protected int mColorWhite;
    protected int mColorOrange;
    protected int mColorGreen;
    protected int mPrimaryColor;

    protected boolean mUseSubject = true;

    private DisplayMetrics metrics;
    protected int mTheme;
    protected boolean mUsingEnterKey = false;

    protected Toast mToast;

    protected void hideToast() {
        if (mToast != null) {
            mToast.cancel();
        }
    }

    protected void replaceToast(String msg) {
        replaceToast(msg, true);
    }

    protected void replaceToast(String msg, boolean showlong) {
        hideToast();
        mToast = Toast.makeText(this, msg, showlong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mToast.show();
    }

    protected Runnable onOpenPGPKeyPublished = new Runnable() {
        @Override
        public void run() {
            Toast.makeText(XmppActivity.this, R.string.openpgp_has_been_published, Toast.LENGTH_SHORT).show();
        }
    };

    private long mLastUiRefresh = 0;
    private Handler mRefreshUiHandler = new Handler();
    private Runnable mRefreshUiRunnable = new Runnable() {
        @Override
        public void run() {
            mLastUiRefresh = SystemClock.elapsedRealtime();
            refreshUiReal();
        }
    };

    protected ConferenceInvite mPendingConferenceInvite = null;


    protected final void refreshUi() {
        final long diff = SystemClock.elapsedRealtime() - mLastUiRefresh;
        if (diff > Config.REFRESH_UI_INTERVAL) {
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            runOnUiThread(mRefreshUiRunnable);
        } else {
            final long next = Config.REFRESH_UI_INTERVAL - diff;
            mRefreshUiHandler.removeCallbacks(mRefreshUiRunnable);
            mRefreshUiHandler.postDelayed(mRefreshUiRunnable, next);
        }
    }

    abstract protected void refreshUiReal();

    protected interface OnValueEdited {
        public void onValueEdited(String value);
    }

    public interface OnPresenceSelected {
        public void onPresenceSelected();
    }

    protected ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            XmppConnectionBinder binder = (XmppConnectionBinder) service;
            xmppConnectionService = binder.getService();
            xmppConnectionServiceBound = true;
            if (!registeredListeners && shouldRegisterListeners()) {
                registerListeners();
                registeredListeners = true;
            }
            onBackendConnected();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            xmppConnectionServiceBound = false;
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        if (!xmppConnectionServiceBound) {
            connectToBackend();
        } else {
            if (!registeredListeners) {
                this.registerListeners();
                this.registeredListeners = true;
            }
            this.onBackendConnected();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    protected boolean shouldRegisterListeners() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return !isDestroyed() && !isFinishing();
        } else {
            return !isFinishing();
        }
    }

    public void connectToBackend() {
        Intent intent = new Intent(this, XmppConnectionService.class);
        intent.setAction("ui");
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (xmppConnectionServiceBound) {
            if (registeredListeners) {
                this.unregisterListeners();
                this.registeredListeners = false;
            }
            unbindService(mConnection);
            xmppConnectionServiceBound = false;
        }
    }

    protected void hideKeyboard() {
        InputMethodManager inputManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);

        View focus = getCurrentFocus();

        if (focus != null) {

            inputManager.hideSoftInputFromWindow(focus.getWindowToken(),
                    InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }

    public boolean hasPgp() {
        return xmppConnectionService.getPgpEngine() != null;
    }

    public void showInstallPgpDialog() {
        Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.openkeychain_required));
        builder.setIconAttribute(android.R.attr.alertDialogIcon);
        builder.setMessage(getText(R.string.openkeychain_required_long));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setNeutralButton(getString(R.string.restart),
                new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (xmppConnectionServiceBound) {
                            unbindService(mConnection);
                            xmppConnectionServiceBound = false;
                        }
                        stopService(new Intent(XmppActivity.this,
                                XmppConnectionService.class));
                        finish();
                    }
                });
        builder.setPositiveButton(getString(R.string.install),
                new OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Uri uri = Uri
                                .parse("market://details?id=org.sufficientlysecure.keychain");
                        Intent marketIntent = new Intent(Intent.ACTION_VIEW,
                                uri);
                        PackageManager manager = getApplicationContext()
                                .getPackageManager();
                        List<ResolveInfo> infos = manager
                                .queryIntentActivities(marketIntent, 0);
                        if (infos.size() > 0) {
                            startActivity(marketIntent);
                        } else {
                            uri = Uri.parse("http://www.openkeychain.org/");
                            Intent browserIntent = new Intent(
                                    Intent.ACTION_VIEW, uri);
                            startActivity(browserIntent);
                        }
                        finish();
                    }
                });
        builder.create().show();
    }

    abstract void onBackendConnected();

    protected void registerListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.setOnConversationListChangedListener((XmppConnectionService.OnConversationUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.setOnAccountListChangedListener((XmppConnectionService.OnAccountUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.setOnCaptchaRequestedListener((XmppConnectionService.OnCaptchaRequested) this);
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.setOnRosterUpdateListener((XmppConnectionService.OnRosterUpdate) this);
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.setOnMucRosterUpdateListener((XmppConnectionService.OnMucRosterUpdate) this);
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.setOnUpdateBlocklistListener((OnUpdateBlocklist) this);
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.setOnShowErrorToastListener((XmppConnectionService.OnShowErrorToast) this);
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.setOnKeyStatusUpdatedListener((OnKeyStatusUpdated) this);
        }
    }

    protected void unregisterListeners() {
        if (this instanceof XmppConnectionService.OnConversationUpdate) {
            this.xmppConnectionService.removeOnConversationListChangedListener();
        }
        if (this instanceof XmppConnectionService.OnAccountUpdate) {
            this.xmppConnectionService.removeOnAccountListChangedListener();
        }
        if (this instanceof XmppConnectionService.OnCaptchaRequested) {
            this.xmppConnectionService.removeOnCaptchaRequestedListener();
        }
        if (this instanceof XmppConnectionService.OnRosterUpdate) {
            this.xmppConnectionService.removeOnRosterUpdateListener();
        }
        if (this instanceof XmppConnectionService.OnMucRosterUpdate) {
            this.xmppConnectionService.removeOnMucRosterUpdateListener();
        }
        if (this instanceof OnUpdateBlocklist) {
            this.xmppConnectionService.removeOnUpdateBlocklistListener();
        }
        if (this instanceof XmppConnectionService.OnShowErrorToast) {
            this.xmppConnectionService.removeOnShowErrorToastListener();
        }
        if (this instanceof OnKeyStatusUpdated) {
            this.xmppConnectionService.removeOnNewKeysAvailableListener();
        }
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_invite_user:
                inviteUser();
                break;
            case R.id.action_create_issue:
                createIssue();
                break;
            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case R.id.action_check_updates:
                if (xmppConnectionService.hasInternetConnection()) {
                    UpdateService task = new UpdateService(this);
                    task.executeOnExecutor(UpdateService.THREAD_POOL_EXECUTOR, "true");
                } else {
                    Toast.makeText(this, R.string.account_status_no_internet, Toast.LENGTH_LONG).show();
                }
                break;
            case R.id.action_accounts:
                final Intent intent = new Intent(getApplicationContext(), EditAccountActivity.class);
                Account mAccount = xmppConnectionService.getAccounts().get(0);
                intent.putExtra("jid", mAccount.getJid().toBareJid().toString());
                intent.putExtra("init", false);
                startActivity(intent);
                break;
            case android.R.id.home:
                finish();
                break;
            case R.id.action_show_qr_code:
                showQrCode();
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EmojiManager.install(new IosEmojiProvider()); // This line needs to be executed before any usage of EmojiTextView or EmojiEditText.
        metrics = getResources().getDisplayMetrics();
        ExceptionHelper.init(getApplicationContext());
        mPrimaryTextColor = ContextCompat.getColor(this, R.color.black87);
        mSecondaryTextColor = ContextCompat.getColor(this, R.color.black54);
        mTertiaryTextColor = ContextCompat.getColor(this, R.color.black12);
        mColorRed = ContextCompat.getColor(this, R.color.red800);
        mColorWarningButton = ContextCompat.getColor(this, R.color.warning_button);
        mColorWhite = ContextCompat.getColor(this, R.color.white70);
        mColorOrange = ContextCompat.getColor(this, R.color.orange500);
        mColorGreen = ContextCompat.getColor(this, R.color.realgreen);
        mPrimaryColor = ContextCompat.getColor(this, R.color.primary);
        mPrimaryBackgroundColor = ContextCompat.getColor(this, R.color.grey50);
        mSecondaryBackgroundColor = ContextCompat.getColor(this, R.color.grey200);
        this.mTheme = findTheme();
        setTheme(this.mTheme);
        this.mUsingEnterKey = usingEnterKey();
        mUseSubject = getPreferences().getBoolean("use_subject", getResources().getBoolean(R.bool.use_subject));
        final ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
        }
    }

    public int getThemeResource(int r_attr_name, int r_drawable_def) {
        int[] attrs = {	r_attr_name };
        TypedArray ta = this.getTheme().obtainStyledAttributes(attrs);

        int res = ta.getResourceId(0, r_drawable_def);
        ta.recycle();

        return res;
    }

    protected boolean isOptimizingBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return !pm.isIgnoringBatteryOptimizations(getPackageName());
        } else {
            return false;
        }
    }

    protected boolean isAffectedByDataSaver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm.isActiveNetworkMetered()
                    && cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        } else {
            return false;
        }
    }

    protected boolean usingEnterKey() {
        return getPreferences().getBoolean("display_enter_key", getResources().getBoolean(R.bool.display_enter_key));
    }

    protected SharedPreferences getPreferences() {
        return PreferenceManager
                .getDefaultSharedPreferences(getApplicationContext());
    }

    public boolean useSubjectToIdentifyConference() {
        return mUseSubject;
    }

    public void switchToConversation(Conversation conversation) {
        switchToConversation(conversation, null, false);
    }

    public void switchToConversation(Conversation conversation, String text,
                                     boolean newTask) {
        switchToConversation(conversation, text, null, false, newTask);
    }

    public void highlightInMuc(Conversation conversation, String nick) {
        switchToConversation(conversation, null, nick, false, false);
    }

    public void privateMsgInMuc(Conversation conversation, String nick) {
        switchToConversation(conversation, null, nick, true, false);
    }

    private void switchToConversation(Conversation conversation, String text, String nick, boolean pm, boolean newTask) {
        Intent viewConversationIntent = new Intent(this,
                ConversationActivity.class);
        viewConversationIntent.setAction(ConversationActivity.ACTION_VIEW_CONVERSATION);
        viewConversationIntent.putExtra(ConversationActivity.CONVERSATION,
                conversation.getUuid());
        if (text != null) {
            viewConversationIntent.putExtra(ConversationActivity.TEXT, text);
        }
        if (nick != null) {
            viewConversationIntent.putExtra(ConversationActivity.NICK, nick);
            viewConversationIntent.putExtra(ConversationActivity.PRIVATE_MESSAGE, pm);
        }
        if (newTask) {
            viewConversationIntent.setFlags(viewConversationIntent.getFlags()
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            viewConversationIntent.setFlags(viewConversationIntent.getFlags()
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        startActivity(viewConversationIntent);
        finish();
    }

    public void switchToContactDetails(Contact contact) {
        switchToContactDetails(contact, null);
    }

    public void switchToContactDetails(Contact contact, String messageFingerprint) {
        Intent intent = new Intent(this, ContactDetailsActivity.class);
        intent.setAction(ContactDetailsActivity.ACTION_VIEW_CONTACT);
        intent.putExtra(EXTRA_ACCOUNT, contact.getAccount().getJid().toBareJid().toString());
        intent.putExtra("contact", contact.getJid().toString());
        intent.putExtra("fingerprint", messageFingerprint);
        startActivity(intent);
    }

    public void switchToAccount(Account account) {
        switchToAccount(account, false);
    }

    public void switchToAccount(Account account, boolean init) {
        Intent intent = new Intent(this, EditAccountActivity.class);
        intent.putExtra("jid", account.getJid().toBareJid().toString());
        intent.putExtra("init", init);
        startActivity(intent);
    }

    protected void inviteToConversation(Conversation conversation) {
        Intent intent = new Intent(getApplicationContext(),
                ChooseContactActivity.class);
        List<String> contacts = new ArrayList<>();
        if (conversation.getMode() == Conversation.MODE_MULTI) {
            for (MucOptions.User user : conversation.getMucOptions().getUsers(false)) {
                Jid jid = user.getRealJid();
                if (jid != null) {
                    contacts.add(jid.toBareJid().toString());
                }
            }
        } else {
            contacts.add(conversation.getJid().toBareJid().toString());
        }
        intent.putExtra("filter_contacts", contacts.toArray(new String[contacts.size()]));
        intent.putExtra("conversation", conversation.getUuid());
        intent.putExtra("multiple", true);
        intent.putExtra("show_enter_jid", true);
        intent.putExtra(EXTRA_ACCOUNT, conversation.getAccount().getJid().toBareJid().toString());
        startActivityForResult(intent, REQUEST_INVITE_TO_CONVERSATION);
    }

    protected void announcePgp(Account account, final Conversation conversation, final Runnable onSuccess) {
        if (account.getPgpId() == 0) {
            choosePgpSignId(account);
        } else {
            String status = null;
            if (manuallyChangePresence()) {
                status = account.getPresenceStatusMessage();
            }
            if (status == null) {
                status = "";
            }
            xmppConnectionService.getPgpEngine().generateSignature(account, status, new UiCallback<Account>() {

                @Override
                public void userInputRequried(PendingIntent pi, Account account) {
                    try {
                        startIntentSenderForResult(pi.getIntentSender(), REQUEST_ANNOUNCE_PGP, null, 0, 0, 0);
                    } catch (final SendIntentException ignored) {
                    }
                }

                @Override
                public void success(Account account) {
                    xmppConnectionService.databaseBackend.updateAccount(account);
                    xmppConnectionService.sendPresence(account);
                    if (conversation != null) {
                        conversation.setNextEncryption(Message.ENCRYPTION_PGP);
                        xmppConnectionService.updateConversation(conversation);
                        refreshUi();
                    }
                    if (onSuccess != null) {
                        runOnUiThread(onSuccess);
                    }
                }

                @Override
                public void error(int error, Account account) {
                    if (error == 0 && account != null) {
                        account.setPgpSignId(0);
                        account.unsetPgpSignature();
                        xmppConnectionService.databaseBackend.updateAccount(account);
                        choosePgpSignId(account);
                    } else {
                        displayErrorDialog(error);
                    }
                }
            });
        }
    }

    protected boolean noAccountUsesPgp() {
        if (!hasPgp()) {
            return true;
        }
        for (Account account : xmppConnectionService.getAccounts()) {
            if (account.getPgpId() != 0) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("deprecation")
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    protected void setListItemBackgroundOnView(View view) {
        int sdk = android.os.Build.VERSION.SDK_INT;
        if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
            view.setBackgroundDrawable(getResources().getDrawable(R.drawable.greybackground));
        } else {
            view.setBackground(getResources().getDrawable(R.drawable.greybackground));
        }
    }

    protected void choosePgpSignId(Account account) {
        xmppConnectionService.getPgpEngine().chooseKey(account, new UiCallback<Account>() {
            @Override
            public void success(Account account1) {
            }

            @Override
            public void error(int errorCode, Account object) {

            }

            @Override
            public void userInputRequried(PendingIntent pi, Account object) {
                try {
                    startIntentSenderForResult(pi.getIntentSender(),
                            REQUEST_CHOOSE_PGP_ID, null, 0, 0, 0);
                } catch (final SendIntentException ignored) {
                }
            }
        });
    }

    protected void displayErrorDialog(final int errorCode) {
        runOnUiThread(new Runnable() {

            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(
                        XmppActivity.this);
                builder.setIconAttribute(android.R.attr.alertDialogIcon);
                builder.setTitle(getString(R.string.error));
                builder.setMessage(errorCode);
                builder.setNeutralButton(R.string.accept, null);
                builder.create().show();
            }
        });

    }

    protected void showAddToRosterDialog(final Conversation conversation) {
        showAddToRosterDialog(conversation.getContact());
    }

    protected void showAddToRosterDialog(final Contact contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(getString(R.string.not_in_roster));
        builder.setNegativeButton(getString(R.string.cancel), null);
        builder.setPositiveButton(getString(R.string.add_contact),
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final Jid jid = contact.getJid();
                        Account account = contact.getAccount();
                        Contact contact = account.getRoster().getContact(jid);
                        xmppConnectionService.createContact(contact);
                    }
                });
        builder.create().show();
    }

    private void showAskForPresenceDialog(final Contact contact) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(contact.getJid().toString());
        builder.setMessage(R.string.request_presence_updates);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.request_now,
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (xmppConnectionServiceBound) {
                            xmppConnectionService.sendPresencePacket(contact
                                    .getAccount(), xmppConnectionService
                                    .getPresenceGenerator()
                                    .requestPresenceUpdatesFrom(contact));
                        }
                    }
                });
        builder.create().show();
    }

    private void warnMutalPresenceSubscription(final Conversation conversation,
                                               final OnPresenceSelected listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(conversation.getContact().getJid().toString());
        builder.setMessage(R.string.without_mutual_presence_updates);
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.ignore, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                conversation.setNextCounterpart(null);
                if (listener != null) {
                    listener.onPresenceSelected();
                }
            }
        });
        builder.create().show();
    }

    protected void quickEdit(String previousValue, int hint, OnValueEdited callback) {
        quickEdit(previousValue, callback, hint, false);
    }

    protected void quickPasswordEdit(String previousValue, OnValueEdited callback) {
        quickEdit(previousValue, callback, R.string.password, true);
    }

    @SuppressLint("InflateParams")
    private void quickEdit(final String previousValue,
                           final OnValueEdited callback,
                           final int hint,
                           boolean password) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = getLayoutInflater().inflate(R.layout.quickedit, null);
        final EditText editor = (EditText) view.findViewById(R.id.editor);
        OnClickListener mClickListener = new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                String value = editor.getText().toString();
                if (!value.equals(previousValue) && value.trim().length() > 0) {
                    callback.onValueEdited(value);
                }
            }
        };
        if (password) {
            editor.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            builder.setPositiveButton(R.string.accept, mClickListener);
        } else {
            builder.setPositiveButton(R.string.ok, mClickListener);
        }
        if (hint != 0) {
            editor.setHint(hint);
        }
        editor.requestFocus();
        editor.setText("");
        if (previousValue != null) {
            editor.getText().append(previousValue);
        }
        builder.setView(view);
        builder.setNegativeButton(R.string.cancel, null);
        builder.create().show();
    }

    public boolean hasStoragePermission(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, requestCode);
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    public boolean hasMicPermission(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, requestCode);
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    public boolean hasLocationPermission(int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, requestCode);
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, requestCode);
                return false;
            } else {
                return true;
            }
        } else {
            return true;
        }
    }

    public void selectPresence(final Conversation conversation,
                               final OnPresenceSelected listener) {
        final Contact contact = conversation.getContact();
        if (conversation.hasValidOtrSession()) {
            SessionID id = conversation.getOtrSession().getSessionID();
            Jid jid;
            try {
                jid = Jid.fromString(id.getAccountID() + "/" + id.getUserID());
            } catch (InvalidJidException e) {
                jid = null;
            }
            conversation.setNextCounterpart(jid);
            listener.onPresenceSelected();
        } else if (!contact.showInRoster()) {
            showAddToRosterDialog(conversation);
        } else {
            final Presences presences = contact.getPresences();
            if (presences.size() == 0) {
                if (!contact.getOption(Contact.Options.TO)
                        && !contact.getOption(Contact.Options.ASKING)
                        && contact.getAccount().getStatus() == Account.State.ONLINE) {
                    showAskForPresenceDialog(contact);
                } else if (!contact.getOption(Contact.Options.TO)
                        || !contact.getOption(Contact.Options.FROM)) {
                    warnMutalPresenceSubscription(conversation, listener);
                } else {
                    conversation.setNextCounterpart(null);
                    listener.onPresenceSelected();
                }
            } else if (presences.size() == 1) {
                String presence = presences.toResourceArray()[0];
                try {
                    conversation.setNextCounterpart(Jid.fromParts(contact.getJid().getLocalpart(), contact.getJid().getDomainpart(), presence));
                } catch (InvalidJidException e) {
                    conversation.setNextCounterpart(null);
                }
                listener.onPresenceSelected();
            } else {
                showPresenceSelectionDialog(presences, conversation, listener);
            }
        }
    }

    private void showPresenceSelectionDialog(Presences presences, final Conversation conversation, final OnPresenceSelected listener) {
        final Contact contact = conversation.getContact();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.choose_presence));
        final String[] resourceArray = presences.toResourceArray();
        Pair<Map<String, String>, Map<String, String>> typeAndName = presences.toTypeAndNameMap();
        final Map<String, String> resourceTypeMap = typeAndName.first;
        final Map<String, String> resourceNameMap = typeAndName.second;
        final String[] readableIdentities = new String[resourceArray.length];
        final AtomicInteger selectedResource = new AtomicInteger(0);
        for (int i = 0; i < resourceArray.length; ++i) {
            String resource = resourceArray[i];
            if (resource.equals(contact.getLastResource())) {
                selectedResource.set(i);
            }
            String type = resourceTypeMap.get(resource);
            String name = resourceNameMap.get(resource);
            if (type != null) {
                if (Collections.frequency(resourceTypeMap.values(), type) == 1) {
                    readableIdentities[i] = UIHelper.tranlasteType(this, type);
                } else if (name != null) {
                    if (Collections.frequency(resourceNameMap.values(), name) == 1
                            || CryptoHelper.UUID_PATTERN.matcher(resource).matches()) {
                        readableIdentities[i] = UIHelper.tranlasteType(this, type) + "  (" + name + ")";
                    } else {
                        readableIdentities[i] = UIHelper.tranlasteType(this, type) + " (" + name + " / " + resource + ")";
                    }
                } else {
                    readableIdentities[i] = UIHelper.tranlasteType(this, type) + " (" + resource + ")";
                }
            } else {
                readableIdentities[i] = resource;
            }
        }
        builder.setSingleChoiceItems(readableIdentities,
                selectedResource.get(),
                new DialogInterface.OnClickListener() {

                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        selectedResource.set(which);
                    }
                });
        builder.setNegativeButton(R.string.cancel, null);
        builder.setPositiveButton(R.string.ok, new OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                try {
                    Jid next = Jid.fromParts(contact.getJid().getLocalpart(), contact.getJid().getDomainpart(), resourceArray[selectedResource.get()]);
                    conversation.setNextCounterpart(next);
                } catch (InvalidJidException e) {
                    conversation.setNextCounterpart(null);
                }
                listener.onPresenceSelected();
            }
        });
        builder.create().show();
    }

    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INVITE_TO_CONVERSATION && resultCode == RESULT_OK) {
            mPendingConferenceInvite = ConferenceInvite.parse(data);
            if (xmppConnectionServiceBound && mPendingConferenceInvite != null) {
                if (mPendingConferenceInvite.execute(this)) {
                    mToast = Toast.makeText(this, R.string.creating_conference, Toast.LENGTH_LONG);
                    mToast.show();
                }
                mPendingConferenceInvite = null;
            }
        }
    }


    private UiCallback<Conversation> adhocCallback = new UiCallback<Conversation>() {
        @Override
        public void success(final Conversation conversation) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switchToConversation(conversation);
                    hideToast();
                }
            });
        }

        @Override
        public void error(final int errorCode, Conversation object) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    replaceToast(getString(errorCode));
                }
            });
        }

        @Override
        public void userInputRequried(PendingIntent pi, Conversation object) {

        }
    };

    public int getTertiaryTextColor() {
        return this.mTertiaryTextColor;
    }

    public int getSecondaryTextColor() {
        return this.mSecondaryTextColor;
    }

    public int getPrimaryTextColor() {
        return this.mPrimaryTextColor;
    }

    public int getWarningTextColor() {
        return this.mColorRed;
    }

    public int getWarningButtonColor() {
        return this.mColorWarningButton;
    }

    public int getOnlineColor() {
        return this.mColorGreen;
    }

    public int getPrimaryBackgroundColor() {
        return this.mPrimaryBackgroundColor;
    }

    public int getSecondaryBackgroundColor() {
        return this.mSecondaryBackgroundColor;
    }

    public int getPixel(int dp) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return ((int) (dp * metrics.density));
    }

    public boolean copyTextToClipboard(String text, int labelResId) {
        ClipboardManager mClipBoardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String label = getResources().getString(labelResId);
        if (mClipBoardManager != null) {
            ClipData mClipData = ClipData.newPlainText(label, text);
            mClipBoardManager.setPrimaryClip(mClipData);
            return true;
        }
        return false;
    }

    protected void registerNdefPushMessageCallback() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcAdapter.setNdefPushMessageCallback(new NfcAdapter.CreateNdefMessageCallback() {
                @Override
                public NdefMessage createNdefMessage(NfcEvent nfcEvent) {
                    return new NdefMessage(new NdefRecord[]{
                            NdefRecord.createUri(getShareableUri()),
                            NdefRecord.createApplicationRecord("de.pixart.messenger")
                    });
                }
            }, this);
        }
    }

    protected boolean neverCompressPictures() {
        return getPreferences().getString("picture_compression", getResources().getString(R.string.picture_compression)).equals("never");
    }

    protected boolean manuallyChangePresence() {
        return getPreferences().getBoolean(SettingsActivity.MANUALLY_CHANGE_PRESENCE,  getResources().getBoolean(R.bool.manually_change_presence));
    }

    protected void unregisterNdefPushMessageCallback() {
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcAdapter.setNdefPushMessageCallback(null, this);
        }
    }

    protected String getShareableUri() {
        return getShareableUri(false);
    }

    protected String getShareableUri(boolean http) {
        return null;
    }

    private void inviteUser() {
        Account mAccount = xmppConnectionService.getAccounts().get(0);
        String user = mAccount.getJid().getLocalpart().toString();
        String domain = mAccount.getJid().getDomainpart().toString();
        String inviteURL = Config.inviteUserURL + user + "/" + domain;
        String inviteText = getString(R.string.InviteText, user);
        Intent intent = new Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, user + " " + getString(R.string.inviteUser_Subject) + " " + getString(R.string.app_name));
        intent.putExtra(Intent.EXTRA_TEXT, inviteText + "\n\n" + inviteURL);
        startActivity(Intent.createChooser(intent, getString(R.string.invite_contact)));
    }

    private void createIssue() {
        String IssueURL = Config.ISSUE_URL;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse(IssueURL));
        startActivity(intent);
    }

    protected void shareLink(boolean http) {
        String uri = getShareableUri(http);
        if (uri == null || uri.isEmpty()) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, getShareableUri(http));
        try {
            startActivity(Intent.createChooser(intent, getText(R.string.share_uri_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_application_to_share_uri, Toast.LENGTH_SHORT).show();
        }
    }

    protected void launchOpenKeyChain(long keyId) {
        PgpEngine pgp = XmppActivity.this.xmppConnectionService.getPgpEngine();
        try {
            startIntentSenderForResult(
                    pgp.getIntentForKey(keyId).getIntentSender(), 0, null, 0,
                    0, 0);
        } catch (Throwable e) {
            Toast.makeText(XmppActivity.this, R.string.openpgp_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (this.getShareableUri() != null) {
            this.registerNdefPushMessageCallback();
        }
    }

    protected int findTheme() {
        Boolean larger = getPreferences().getBoolean("use_larger_font", getResources().getBoolean(R.bool.use_larger_font));

        if (larger) {
            return R.style.ConversationsTheme_LargerText;
        } else {
            return R.style.ConversationsTheme;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        this.unregisterNdefPushMessageCallback();
    }

    protected void showQrCode() {
        final String uri = getShareableUri();
        if (uri == null || uri.isEmpty()) {
            return;
        }
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getSize(size);
        final int width = (size.x < size.y ? size.x : size.y);
        Bitmap bitmap = BarcodeProvider.create2dBarcodeBitmap(uri, width);
        ImageView view = new ImageView(this);
        view.setBackgroundColor(Color.WHITE);
        view.setImageBitmap(bitmap);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(view);
        builder.create().show();
    }

    protected Account extractAccount(Intent intent) {
        String jid = intent != null ? intent.getStringExtra(EXTRA_ACCOUNT) : null;
        try {
            return jid != null ? xmppConnectionService.findAccountByJid(Jid.fromString(jid)) : null;
        } catch (InvalidJidException e) {
            return null;
        }
    }

    public static class ConferenceInvite {
        private String uuid;
        private List<Jid> jids = new ArrayList<>();

        public static ConferenceInvite parse(Intent data) {
            ConferenceInvite invite = new ConferenceInvite();
            invite.uuid = data.getStringExtra("conversation");
            if (invite.uuid == null) {
                return null;
            }
            try {
                if (data.getBooleanExtra("multiple", false)) {
                    String[] toAdd = data.getStringArrayExtra("contacts");
                    for (String item : toAdd) {
                        invite.jids.add(Jid.fromString(item));
                    }
                } else {
                    invite.jids.add(Jid.fromString(data.getStringExtra("contact")));
                }
            } catch (final InvalidJidException ignored) {
                return null;
            }
            return invite;
        }

        public boolean execute(XmppActivity activity) {
            XmppConnectionService service = activity.xmppConnectionService;
            Conversation conversation = service.findConversationByUuid(this.uuid);
            if (conversation == null) {
                return false;
            }
            if (conversation.getMode() == Conversation.MODE_MULTI) {
                for (Jid jid : jids) {
                    service.invite(conversation, jid);
                }
                return false;
            } else {
                jids.add(conversation.getJid().toBareJid());
                return service.createAdhocConference(conversation.getAccount(), null, jids, activity.adhocCallback);
            }
        }
    }

    public AvatarService avatarService() {
        return xmppConnectionService.getAvatarService();
    }

    class BitmapWorkerTask extends AsyncTask<Message, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Message message = null;

        public BitmapWorkerTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Message... params) {
            if (isCancelled()) {
                return null;
            }
            message = params[0];
            try {
                return xmppConnectionService.getFileBackend().getThumbnail(
                        message, (int) (metrics.density * 288), false);
            } catch (FileNotFoundException e) {
                return null;
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }

    public void loadBitmap(Message message, ImageView imageView) {
        Bitmap bm;
        try {
            bm = xmppConnectionService.getFileBackend().getThumbnail(message,
                    (int) (metrics.density * 288), true);
        } catch (FileNotFoundException e) {
            bm = null;
        }
        if (bm != null) {
            cancelPotentialWork(message, imageView);
            imageView.setImageBitmap(bm);
            imageView.setBackgroundColor(0x00000000);
        } else {
            if (cancelPotentialWork(message, imageView)) {
                imageView.setBackgroundColor(0xff333333);
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
                final AsyncDrawable asyncDrawable = new AsyncDrawable(
                        getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.executeOnExecutor(BitmapWorkerTask.THREAD_POOL_EXECUTOR, message);
                } catch (final RejectedExecutionException ignored) {
                    ignored.printStackTrace();
                }
            }
        }
    }

    public static boolean cancelPotentialWork(Message message, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Message oldMessage = bitmapWorkerTask.message;
            if (oldMessage == null || message != oldMessage) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap,
                             BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(
                    bitmapWorkerTask);
        }

        public BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }
}