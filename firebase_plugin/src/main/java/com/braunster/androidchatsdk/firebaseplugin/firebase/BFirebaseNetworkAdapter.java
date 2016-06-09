/*
 * Created by Itzik Braun on 12/3/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 3/12/15 4:34 PM
 */

package com.braunster.androidchatsdk.firebaseplugin.firebase;

import android.content.Context;
import android.graphics.Bitmap;
import android.support.annotation.NonNull;

import com.braunster.androidchatsdk.firebaseplugin.R;
import com.braunster.androidchatsdk.firebaseplugin.firebase.parse.ParseUtils;
import com.braunster.androidchatsdk.firebaseplugin.firebase.parse.PushUtils;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.Utils.helper.ChatSDKUiHelper;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.network.AbstractNetworkAdapter;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BFacebookManager;
import com.braunster.chatsdk.network.BFirebaseDefines;
import com.braunster.chatsdk.network.TwitterManager;
import com.braunster.chatsdk.object.BError;
import com.braunster.chatsdk.object.SaveImageProgress;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FacebookAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.TwitterAuthProvider;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DatabaseError;
import com.parse.Parse;
import com.parse.ParseInstallation;
import com.parse.PushService;

import org.jdeferred.Deferred;
import org.jdeferred.DoneCallback;
import org.jdeferred.FailCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import java.security.AuthProvider;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

import static com.braunster.chatsdk.network.BDefines.BAccountType.Anonymous;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Custom;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Facebook;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Password;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Register;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Twitter;
import static com.braunster.chatsdk.network.BDefines.Keys;

public abstract class BFirebaseNetworkAdapter extends AbstractNetworkAdapter {

    private static final String TAG = BFirebaseNetworkAdapter.class.getSimpleName();
    private static boolean DEBUG = Debug.BFirebaseNetworkAdapter;

    public BFirebaseNetworkAdapter(Context context){
        super(context);

        // Adding the manager that will handle all the incoming events.
        FirebaseEventsManager eventManager = FirebaseEventsManager.getInstance();
        setEventManager(eventManager);

        // Parse init
        Parse.initialize(context, context.getString(R.string.parse_app_id), context.getString(R.string.parse_client_key));
        ParseInstallation.getCurrentInstallation().saveInBackground();

    }


    /**
     * Indicator for the current state of the authentication process.
     **/
    protected enum AuthStatus{
        IDLE {
            @Override
            public String toString() {
                return "Idle";
            }
        },
        AUTH_WITH_MAP{
            @Override
            public String toString() {
                return "Auth with map";
            }
        },
        HANDLING_F_USER{
            @Override
            public String toString() {
                return "Handling F user";
            }
        },
        UPDATING_USER{
            @Override
            public String toString() {
                return "Updating user";
            }
        },
        PUSHING_USER{
            @Override
            public String toString() {
                return "Pushing user";
            }
        },
        CHECKING_IF_AUTH{
            @Override
            public String toString() {
                return "Checking if Authenticated";
            }
        }
    }

    protected AuthStatus authingStatus = AuthStatus.IDLE;

    public AuthStatus getAuthingStatus() {
        return authingStatus;
    }

    public boolean isAuthing(){
        return authingStatus != AuthStatus.IDLE;
    }

    protected void resetAuth(){
        authingStatus = AuthStatus.IDLE;
    }

    @Override
    public Promise<Object, BError, Void> authenticateWithMap(final Map<String, Object> details) {
        if (DEBUG) Timber.v("authenticateWithMap, KeyType: %s", details.get(BDefines.Prefs.LoginTypeKey));

        final Deferred<Object, BError, Void> deferred = new DeferredObject<>();
        
        if (isAuthing())
        {
            if (DEBUG) Timber.d("Already Authing!, Status: %s", authingStatus.name());
            deferred.reject(BError.getError(BError.Code.AUTH_IN_PROCESS, "Cant run two auth in parallel"));
            return deferred.promise();
        }

        authingStatus = AuthStatus.AUTH_WITH_MAP;

        DatabaseReference ref = FirebasePaths.firebaseRef();

        /*Firebase.AuthResultHandler authResultHandler = new Firebase.AuthResultHandler() {
            @Override
            public void onAuthenticated(final FirebaseUser authData) {
                handleFAUser(authData).then(new DoneCallback<BUser>() {
                    @Override
                    public void onDone(BUser bUser) {
                        resetAuth();
                        deferred.resolve(authData);
                        resetAuth();
                    }
                }, new FailCallback<BError>() {
                    @Override
                    public void onFail(BError bError) {
                        resetAuth();
                        deferred.reject(bError);
                    }
                });
            }

            @Override
            public void onAuthenticationError(DatabaseError firebaseError) {
                if (DEBUG) Timber.e("Error login in, Name: %s", firebaseError.getMessage());
                resetAuth();
                deferred.reject(getFirebaseError(firebaseError));
            }
        };*/

        OnCompleteListener<AuthResult> resultHandler = new OnCompleteListener<AuthResult>() {
            @Override
            public void onComplete(@NonNull Task<AuthResult> task) {
                resetAuth();

                if(task.isSuccessful()) {
                    deferred.resolve(task.getResult().getUser());
                    resetAuth();
                } else {
                    deferred.reject(BError.getExceptionError(task.getException()));
                }
            }
        };

        AuthCredential credential = null;

        switch ((Integer)details.get(BDefines.Prefs.LoginTypeKey))
        {
            case Facebook:

                if (DEBUG) Timber.d(TAG, "authing with fb, AccessToken: %s", BFacebookManager.userFacebookAccessToken);

                credential = FacebookAuthProvider.getCredential(BFacebookManager.userFacebookAccessToken);
                FirebaseAuth.getInstance().signInWithCredential(credential).addOnCompleteListener(resultHandler);

                break;

            case Twitter:

                if (DEBUG) Timber.d("authing with twitter, AccessToken: %s", TwitterManager.accessToken.getToken());

                credential = TwitterAuthProvider.getCredential(TwitterManager.accessToken.getToken(), TwitterManager.accessToken.getSecret());
                FirebaseAuth.getInstance().signInWithCredential(credential).addOnCompleteListener(resultHandler);

                break;

            case Password:
                FirebaseAuth.getInstance().signInWithEmailAndPassword((String) details.get(BDefines.Prefs.LoginEmailKey),
                        (String) details.get(BDefines.Prefs.LoginPasswordKey)).addOnCompleteListener(resultHandler);
                break;
            case  Register:
                FirebaseAuth.getInstance().createUserWithEmailAndPassword((String) details.get(BDefines.Prefs.LoginEmailKey),
                        (String) details.get(BDefines.Prefs.LoginPasswordKey)).addOnCompleteListener(new OnCompleteListener<AuthResult>() {
                            @Override
                            public void onComplete(@NonNull Task<AuthResult> task) {
                                
                                // Resetting so we could auth again.
                                resetAuth();

                                if(task.isSuccessful()) {
                                    //Authing the user after creating it.
                                    details.put(BDefines.Prefs.LoginTypeKey, Password);
                                    authenticateWithMap(details).done(new DoneCallback<Object>() {
                                        @Override
                                        public void onDone(Object o) {
                                            deferred.resolve(o);
                                        }
                                    }).fail(new FailCallback<BError>() {
                                        @Override
                                        public void onFail(BError bError) {
                                            deferred.reject(bError);
                                        }
                                    });
                                } else {
                                    if (DEBUG) Timber.e("Error login in, Name: %s", task.getException().getMessage());
                                    resetAuth();
                                    deferred.reject(getFirebaseError(DatabaseError.fromException(task.getException())));
                                }
                            }
                        });
                break;

            case Anonymous:
                FirebaseAuth.getInstance().signInAnonymously().addOnCompleteListener(resultHandler);
                break;

            case Custom:
                FirebaseAuth.getInstance().signInWithCustomToken((String) details.get(BDefines.Prefs.TokenKey)).addOnCompleteListener(resultHandler);

                break;


            default:
                if (DEBUG) Timber.d("No login type was found");
                deferred.reject(BError.getError(BError.Code.NO_LOGIN_TYPE, "No matching login type was found"));
                break;
        }


        return deferred.promise();
    }

    public abstract Promise<BUser, BError, Void> handleFAUser(final FirebaseUser authData);


    @Override
    public Promise<String[], BError, SaveImageProgress> saveBMessageWithImage(BMessage message) {
        return ParseUtils.saveBMessageWithImage(message);
    }

    @Override
    public Promise<String[], BError, SaveImageProgress> saveImageWithThumbnail(String path, int thumbnailSize) {
        return ParseUtils.saveImageFileToParseWithThumbnail(path, thumbnailSize);
    }

    @Override
    public Promise<String, BError, SaveImageProgress> saveImage(String path) {
        return ParseUtils.saveImageToParse(path);
    }

    @Override
    public Promise<String, BError, SaveImageProgress> saveImage(Bitmap b, int size) {
        return ParseUtils.saveImageToParse(b, size);
    }

    @Override
    public String getServerURL() {
        return BDefines.ServerUrl;
    }

    
    
    protected void pushForMessage(final BMessage message){
        if (!parseEnabled())
            return;

        if (DEBUG) Timber.v("pushForMessage");
        if (message.getBThreadOwner().getTypeSafely() == BThread.Type.Private) {

            // Loading the message from firebase to get the timestamp from server.
            DatabaseReference firebase = FirebasePaths.threadRef(
                    message.getBThreadOwner().getEntityID());
            firebase = FirebasePaths.appendPathComponent(firebase, BFirebaseDefines.Path.BMessagesPath);
            firebase = FirebasePaths.appendPathComponent(firebase, message.getEntityID());

            firebase.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Long date = null;
                    try {
                        date = (Long) snapshot.child(Keys.BDate).getValue();
                    } catch (ClassCastException e) {
                        date = (((Double)snapshot.child(Keys.BDate).getValue()).longValue());
                    }
                    finally {
                        if (date != null)
                        {
                            message.setDate(new Date(date));
                            DaoCore.updateEntity(message);
                        }
                    }

                    // If we failed to get date dont push.
                    if (message.getDate()==null)
                        return;

                    BUser currentUser = currentUserModel();
                    List<BUser> users = new ArrayList<BUser>();

                    for (BUser user : message.getBThreadOwner().getUsers())
                        if (!user.equals(currentUser))
                            if (user.getOnline() == null || !user.getOnline())
                            {
                                users.add(user);
                            }

                    pushToUsers(message, users);
                }

                @Override
                public void onCancelled(DatabaseError firebaseError) {

                }
            });
        }
    }

    protected void pushToUsers(BMessage message, List<BUser> users){
        if (DEBUG) Timber.v("pushToUsers");

        if (!parseEnabled() || users.size() == 0)
            return;

        // We're identifying each user using push channels. This means that
        // when a user signs up, they register with parse on a particular
        // channel. In this case user_[user id] this means that we can
        // send a push to a specific user if we know their user id.
        List<String> channels = new ArrayList<String>();
        for (BUser user : users)
            channels.add(user.getPushChannel());

        PushUtils.sendMessage(message, channels);
    }

    public void subscribeToPushChannel(String channel){
        if (!parseEnabled())
            return;

        try {
            PushService.subscribe(context, channel, ChatSDKUiHelper.getInstance().mainActivity);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();

            if (channel.contains("%3A"))
                PushService.subscribe(context, channel.replace("%3A", "_"), ChatSDKUiHelper.getInstance().mainActivity);
            else if (channel.contains("%253A"))
                PushService.subscribe(context, channel.replace("%253A", "_"), ChatSDKUiHelper.getInstance().mainActivity);
        }
    }

    public void unsubscribeToPushChannel(String channel){
        if (!parseEnabled())
            return;

        PushService.unsubscribe(context, channel);
    }


    /** Convert the firebase error to a {@link com.braunster.chatsdk.object.BError BError} object. */
    public static BError getFirebaseError(DatabaseError error){
        String errorMessage = "";

        int code = 0;

        switch (error.getCode())
        {
            case DatabaseError.EMAIL_TAKEN:
                code = BError.Code.EMAIL_TAKEN;
                errorMessage = "Email is taken.";
                break;

            case DatabaseError.INVALID_EMAIL:
                code = BError.Code.INVALID_EMAIL;
                errorMessage = "Invalid Email.";
                break;

            case DatabaseError.INVALID_PASSWORD:
                code = BError.Code.INVALID_PASSWORD;
                errorMessage = "Invalid Password";
                break;

            case DatabaseError.USER_DOES_NOT_EXIST:
                code = BError.Code.USER_DOES_NOT_EXIST;
                errorMessage = "Account not found.";
                break;

            case DatabaseError.NETWORK_ERROR:
                code = BError.Code.NETWORK_ERROR;
                errorMessage = "Network Error.";
                break;

            case DatabaseError.INVALID_CREDENTIALS:
                code = BError.Code.INVALID_CREDENTIALS;
                errorMessage = "Invalid credentials.";
                break;

            case DatabaseError.EXPIRED_TOKEN:
                code = BError.Code.EXPIRED_TOKEN;
                errorMessage = "Expired Token.";
                break;

            case DatabaseError.OPERATION_FAILED:
                code = BError.Code.OPERATION_FAILED;
                errorMessage = "Operation failed";
                break;

            case DatabaseError.PERMISSION_DENIED:
                code = BError.Code.PERMISSION_DENIED;
                errorMessage = "Permission denied";
                break;

            default: errorMessage = "An Error Occurred.";
        }

        return new BError(code, errorMessage, error);
    }
}
