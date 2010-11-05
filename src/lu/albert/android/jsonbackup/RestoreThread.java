package lu.albert.android.jsonbackup;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Date;

import lu.albert.android.jsonbackup.schema.ContactColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.provider.Contacts;
import android.provider.ContactsContract;
import android.provider.Contacts.GroupMembership;
import android.provider.Contacts.Groups;
import android.provider.Contacts.Organizations;
import android.provider.Contacts.People;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;

/**
 * The thread which reads the input file and restores the contacts.
 * 
 * Note that the file is *streamed* from the disk, and the contacts are read
 * on-the-fly. In other words, the application does not perform this in a
 * "Transaction". If a crash occurs, only the contacts that have been read
 * will be restored, and those that have been deleted beforehand will be lost!
 * 
 * You can however re-start the operation anytime.
 * 
 * @author Michel Albert <michel.albert@statec.etat.lu>
 */
public class RestoreThread extends Thread{
	
	private static final String ACCOUNT_TYPE = "com.android.exchange";
	private static final String ACCOUNT_NAME = "changeThis";
	Handler mRestoreHandler;
	final static int STATE_DONE = 0;
	final static int STATE_RUNNING = 1;
	int mState;
	int total;
	private JsonBackup mParent;
	private boolean mKeepRunning;

	/**
	 * Constructor
	 * 
	 * @param dialog_handler A handler which is used to communicate with the progress dialog
	 * @param parent The main Activity (UI) class
	 */
	RestoreThread(Handler dialog_handler, JsonBackup parent ) {
		mRestoreHandler = dialog_handler;
		mParent = parent;
		mKeepRunning = true;
	}

	/**
	 * sets the current state for the thread, used to stop the thread
	 * @param state The new state
	 */
	public void setState(int state) {
		mState = state;
	}
	
	public void run() {

		mParent.getContentResolver().delete(People.CONTENT_URI, null, null);
		File file1 = null;
		file1 = new File( mParent.getStorageFolder(), JsonBackup.FILE_NAME );
		
		this.readStream(file1);
		
		mState = STATE_DONE;
	}

	/**
	 * Read the on-disk data by streaming it without creating a complete JSONArray in memory
	 * 
	 * @param in_file The input file
	 */
	private void readStream(File in_file) {
		
		StringBuilder data = new StringBuilder();
		FileInputStream file_stream = null;
		JSONObject contact = null;
		
		try {
			file_stream = new FileInputStream(in_file);
			BufferedInputStream stream_buffer = new BufferedInputStream(file_stream);
			InputStreamReader reader = new InputStreamReader(stream_buffer, Charset.forName("UTF-8"));
			
			int data_available = reader.read();
			int braceDepth = 0; // used to trigger JSON decoding
			boolean contactOpen = false; // used to trigger JSON decoding
			long count = 0; // characters read
			
			while( data_available != -1 ) {
				char theChar = (char) data_available;
				
				/*
				 * When a new object opens on the root level, then we have the
				 * beginning of a new contact. Set the appropriate flag and
				 * empty the buffer (create a new one)
				 */
				if (theChar == '{') {
					if (braceDepth == 0) {
						contactOpen = true;
						if ( !mKeepRunning ){
							break;
						}
						data = new StringBuilder();
					}
					braceDepth += 1;
				} else if (theChar == '}') {
					braceDepth -= 1;
				}
				data.append(theChar);
				
				/*
				 * So, the structure depths has been reduced to 0. So we are
				 * back on the "contacts" level. If we still have a contact
				 * open, we take that string, parse it and store the
				 * resulting contact.
				 * 
				 *  Additionally, we let the user know that the contact has
				 *  been restored.
				 */
				if (braceDepth == 0 && contactOpen) {
					try {
						contact = new JSONObject( data.toString() );
						store_contact( contact );
						
						Message msg = mRestoreHandler.obtainMessage(JsonBackup.RESTORE_MSG_INFO);
						Bundle b = new Bundle();
						b.putString("name", contact.getString( ContactColumns.NAME ));
						msg.setData(b);
						mRestoreHandler.sendMessage(msg);
					} catch (JSONException e){
						showError(e.getMessage());
						e.printStackTrace();
					}
					contactOpen = false;
				}
				
				data_available = reader.read();
				count += 1;
				
				/*
				 * Update the progress dialog
				 */
				if ( count % 100 == 0) {
					Message msg = mRestoreHandler.obtainMessage(JsonBackup.RESTORE_MSG_PROGRESS);
					Bundle b = new Bundle();
					b.putLong("position", count);
					b.putLong("total", in_file.length());
					msg.setData(b);
					mRestoreHandler.sendMessage(msg);
				}

			}
			
			/*
			 * Update the progress dialog, ensuring that it properly triggers
			 * it's end-of-life ( as we only update the progress every so
			 * often, it could happen that the value does not meet the EOL
			 * criteria.
			 */
			Message msg = mRestoreHandler.obtainMessage(JsonBackup.RESTORE_MSG_PROGRESS);
			Bundle b = new Bundle();
			b.putLong("position", in_file.length());
			b.putLong("total", in_file.length());
			msg.setData(b);
			mRestoreHandler.sendMessage(msg);
			
			/*
			 * Clean up
			 */
			reader.close();
			stream_buffer.close();
			file_stream.close();
		} catch (FileNotFoundException e) {
			showError(e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			showError(e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Create a new contact on the device given a JSON object
	 * 
	 * @param contact The contact to be created
	 * @throws JSONException when unable to decode the JSON elements
	 */
	private void store_contact_pre2(JSONObject contact) throws JSONException {
		
		ContentResolver cr = mParent.getContentResolver();
		
		/*
		 * Store base values
		 */
		ContentValues values = new ContentValues();
		values.put( People._ID, contact.getString( ContactColumns.ID ) );
		values.put( People.NAME, contact.getString( ContactColumns.NAME ) );
		values.put( People.TIMES_CONTACTED, contact.getString( ContactColumns.TIMES_CONTACTED ) );
		// TODO: values.put( People.DISPLAY_NAME, contact.getString(ContactColumns.DISPLAY_NAME) );
		values.put( People.STARRED, contact.getInt( ContactColumns.STARRED ) );
		
		Uri uri = Contacts.People
		  .createPersonInMyContactsGroup(cr, values);
		
		if ( uri == null) {
			System.err.println("Failed to create contact for " + contact.getString( ContactColumns.NAME ));
			return;
		}
		
		/*
		 * Store phone numbers
		 */
		JSONArray phones = contact.getJSONArray( ContactColumns.PHONE_NUMBERS );
		for( int i = 0; i < phones.length(); ++i ){
			JSONObject phone = phones.getJSONObject(i);
			if (phone == null ){
				continue;
			}
			
			Uri phoneUri = null;
			phoneUri = Uri.withAppendedPath(uri, People.Phones.CONTENT_DIRECTORY);
			values.clear();
			values.put(People.Phones.TYPE, phone.getInt( ContactColumns.PhoneColumns.TYPE ));
			values.put(People.Phones.NUMBER, phone.getString( ContactColumns.PhoneColumns.NUMBER ));
			values.put(People.Phones.ISPRIMARY, (phone.getBoolean(ContactColumns.PhoneColumns.IS_PRIMARY ) ? 1 : 0));
			cr.insert(phoneUri, values);
		}
		phones = null;
		
		/*
		 * Store photo
		 */
		JSONArray photos = contact.getJSONArray( ContactColumns.PHOTOS );
		if ( photos.length() > 0 ) {
			String photo = photos.getString(0);
			if (photo != null && !photo.equals("") ){
				try {
					Contacts.People.setPhotoData(cr, uri, Base64.decode(photo));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		photos = null;
	}
	
	private static final String[] GROUPS_PROJECTION = new String[] {
        Groups._ID,
    };
	
    private static Uri createPersonInMyContactsGroup(ContentResolver resolver,
            ContentValues values) {

        Uri contactUri = resolver.insert(People.CONTENT_URI, values);
        if (contactUri == null) {
            //Log.e(TAG, "Failed to create the contact");
            return null;
        }

        if (addToMyContactsGroup(resolver, ContentUris.parseId(contactUri)) == null) {
            resolver.delete(contactUri, null, null);
            return null;
        }
        return contactUri;
    }
    
    private static Uri addToMyContactsGroup(ContentResolver resolver, long personId) {
        long groupId = tryGetMyContactsGroupId(resolver);
        if (groupId == 0) {
            throw new IllegalStateException("Failed to find the My Contacts group");
        }

        return addToGroup(resolver, personId, groupId);
    }
    
    public static long tryGetMyContactsGroupId(ContentResolver resolver) {
        Cursor groupsCursor = resolver.query(Groups.CONTENT_URI, GROUPS_PROJECTION,
                Groups.SYSTEM_ID + "='" + Groups.GROUP_MY_CONTACTS + "'", null, null);
        if (groupsCursor != null) {
            try {
                if (groupsCursor.moveToFirst()) {
                    return groupsCursor.getLong(0);
                }
            } finally {
                groupsCursor.close();
            }
        }
        return 0;
    }

    public static Uri addToGroup(ContentResolver resolver, long personId, String groupName) {
        long groupId = 0;
        Cursor groupsCursor = resolver.query(Groups.CONTENT_URI, GROUPS_PROJECTION,
                Groups.NAME + "=?", new String[] { groupName }, null);
        if (groupsCursor != null) {
            try {
                if (groupsCursor.moveToFirst()) {
                    groupId = groupsCursor.getLong(0);
                }
            } finally {
                groupsCursor.close();
            }
        }

        if (groupId == 0) {
            throw new IllegalStateException("Failed to find the My Contacts group");
        }

        return addToGroup(resolver, personId, groupId);
    }
    

    public static Uri addToGroup(ContentResolver resolver, long personId, long groupId) {
        ContentValues values = new ContentValues();
        values.put(GroupMembership.PERSON_ID, personId);
        values.put(GroupMembership.GROUP_ID, groupId);
        return resolver.insert(GroupMembership.CONTENT_URI, values);
    }
	
    
//	private void setOrganization(String contactId, ContentValues values) {
//
//		String where = ContactsContract.Data.MIMETYPE + " = ? AND "
//				+ ContactsContract.CommonDataKinds.StructuredName.CONTACT_ID
//				+ " = ?";
//
//		String[] whereArgs = new String[] {
//				ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
//				contactId };
//
//
//		try {
//			ContentResolver cr = mParent.getContentResolver();
//			cr.update(ContactsContract.Data.CONTENT_URI, values, where,
//					whereArgs);
//			cr.insert(url, values);
//		} catch (Exception e) {
//			String s = e.getMessage();
//		}
//	}
   
    

	/**
	 * Create a new contact on the device given a JSON object
	 * 
	 * @param contact The contact to be created
	 * @throws JSONException when unable to decode the JSON elements
	 */
	private void store_contact_newApi(JSONObject contact) throws JSONException {
		
		ContentResolver cr = mParent.getContentResolver();
		ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();
		
		ContentValues values = new ContentValues();
		
		/*
		 * Store base values
		 */
	    // Raw Contact
	    ContentProviderOperation.Builder builder = ContentProviderOperation.newInsert(RawContacts.CONTENT_URI);
	    builder.withValue(RawContacts.ACCOUNT_NAME, ACCOUNT_NAME);
	    builder.withValue(RawContacts.ACCOUNT_TYPE, ACCOUNT_TYPE);
	    builder.withValue(RawContacts.SYNC1, "new_26_" + new Date().getTime());
	    builder.withValue(RawContacts.VERSION, 2);
	    
		setStringValue(values, ContactsContract.RawContacts.TIMES_CONTACTED, ContactColumns.TIMES_CONTACTED, contact);
		setIntValue(values, ContactsContract.RawContacts.STARRED, ContactColumns.STARRED, contact);
		setStringValue(values, ContactsContract.RawContacts.CUSTOM_RINGTONE, ContactColumns.CUSTOM_RING_TONE, contact);
		setLongValue(values, ContactsContract.RawContacts.LAST_TIME_CONTACTED, ContactColumns.LAST_TIME_CONTACTED, contact);
		setIntValue(values, ContactsContract.RawContacts.SEND_TO_VOICEMAIL, ContactColumns.SEND_TO_VOICEMAIL, contact);
		
	    builder.withValues(values);
	    operationList.add(builder.build());
	    values.clear();
		
		

		//setStringValue(values, People._ID, ContactColumns.ID, contact);
		
	    // Name
	    builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
	    builder.withValueBackReference(ContactsContract.CommonDataKinds.StructuredName.RAW_CONTACT_ID, 0);
	    builder.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);

		//handle name differently
		String name = contact.optString(ContactColumns.NAME);
		if (null != name && name.length() > 0 && name.contains(",")) {
			String[] nameParts = name.split(",");
			if (nameParts.length == 2) {
				builder.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, nameParts[1].trim());
				builder.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, nameParts[0].trim());
			} else {
				builder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name);		
			}
		}
		setStringValue(values, ContactsContract.CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, ContactColumns.PHONETIC_NAME, contact);

	    operationList.add(builder.build());
	    values.clear();
	    
	    builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI);
	    builder.withValueBackReference(ContactsContract.CommonDataKinds.Note.RAW_CONTACT_ID, 0);
	    builder.withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
		setStringValue(values, ContactsContract.CommonDataKinds.Note.NOTE, ContactColumns.NOTES, contact);
		
		//setStringValue(values, ContactsContract.CommonDataKinds.Photo.PHOTO_VERSION, ContactColumns.PHOTO_VERSION, contact);

		
		
		//Uri uri = createPersonInMyContactsGroup(cr, values);
		Uri uri = cr.insert(People.CONTENT_URI, values);
		long contactId = ContentUris.parseId(uri);
		
		if ( uri == null) {
			System.err.println("Failed to create contact for " + contact.getString( ContactColumns.NAME ));
			return;
		}
		
		//special treatment for display name as it is ignored when set at insert time
		values.clear();
		setStringValue(values, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, ContactColumns.DISPLAY_NAME, contact);
		String where = ContactsContract.Data.MIMETYPE + " = ? AND "
			+ ContactsContract.CommonDataKinds.StructuredName.CONTACT_ID
			+ " = ?";
	
		String[] whereArgs = new String[] {
			ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
			Long.toString(contactId) };
		cr.update(ContactsContract.Data.CONTENT_URI, values, where, whereArgs);
		
		
		/*
		 * Store phone numbers
		 */
		JSONArray phones = contact.getJSONArray( ContactColumns.PHONE_NUMBERS );
		for( int i = 0; i < phones.length(); ++i ){
			JSONObject phone = phones.getJSONObject(i);
			if (phone == null ){
				continue;
			}
			
			Uri phoneUri = null;
			phoneUri = Uri.withAppendedPath(uri, People.Phones.CONTENT_DIRECTORY);
			values.clear();
			setIntValue(values, People.Phones.TYPE, ContactColumns.PhoneColumns.TYPE, phone);
			setStringValue(values, People.Phones.NUMBER, ContactColumns.PhoneColumns.NUMBER, phone);
			setBooleanValue(values, People.Phones.ISPRIMARY, ContactColumns.PhoneColumns.IS_PRIMARY, phone);
			setStringValue(values, People.Phones.LABEL, ContactColumns.PhoneColumns.LABEL, phone);
			setStringValue(values, People.Phones.NUMBER_KEY, ContactColumns.PhoneColumns.NUMBER_KEY, phone);
			
			cr.insert(phoneUri, values);
		}
		phones = null;
		
		/*
		 * Store photo
		 */
		JSONArray photos = contact.getJSONArray( ContactColumns.PHOTOS );
		if ( photos.length() > 0 ) {
			String photo = photos.getString(0);
			if (photo != null && !photo.equals("") ){
				try {
					Contacts.People.setPhotoData(cr, uri, Base64.decode(photo));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		photos = null;
		
		/*
		 * Store addresses
		 */
		JSONArray addresses = contact.getJSONArray( ContactColumns.CONTACT_METHODS );
		for( int i = 0; i < addresses.length(); ++i ){
			JSONObject address = addresses.getJSONObject(i);
			if (address == null ){
				continue;
			}
			
			Uri addressUri = null;
			addressUri = Uri.withAppendedPath(uri, People.ContactMethods.CONTENT_DIRECTORY);
			values.clear();
			setStringValue(values, People.ContactMethods.KIND, ContactColumns.ContactMethodColumns.KIND, address);
			setStringValue(values, People.ContactMethods.DATA, ContactColumns.ContactMethodColumns.DATA, address);
			setIntValue(values, People.ContactMethods.TYPE, ContactColumns.ContactMethodColumns.TYPE, address);
			setBooleanValue(values, People.ContactMethods.ISPRIMARY, ContactColumns.ContactMethodColumns.IS_PRIMARY, address);

			setStringValue(values, People.ContactMethods.AUX_DATA, ContactColumns.ContactMethodColumns.AUX_DATA, address);
			setStringValue(values, People.ContactMethods.LABEL, ContactColumns.ContactMethodColumns.LABEL, address);
			
			cr.insert(addressUri, values);
		}
		addresses = null;
	
		
		/*
		 * Store organizations
		 */
		JSONArray organisations = contact.getJSONArray( ContactColumns.ORGANIZATIONS );
		for( int i = 0; i < organisations.length(); ++i ){
			JSONObject orga = organisations.getJSONObject(i);
			if (orga == null ){
				continue;
			}
			
			Uri orgaUri = null;
			//orgaUri = Uri.withAppendedPath(uri, Contacts.Organizations.CONTENT_DIRECTORY);
			
//			values.clear();
//			setStringValue(values, Contacts.Organizations.COMPANY, ContactColumns.OrganizationColumns.COMPANY, orga);
//			setBooleanValue(values, Contacts.Organizations.ISPRIMARY, ContactColumns.OrganizationColumns.IS_PRIMARY, orga);
//			setStringValue(values, Contacts.Organizations.LABEL, ContactColumns.OrganizationColumns.LABEL, orga);
//			setStringValue(values, Contacts.Organizations.TITLE, ContactColumns.OrganizationColumns.TITLE, orga);
//			setIntValue(values, Contacts.Organizations.TYPE, ContactColumns.OrganizationColumns.TYPE, orga);

			//cr.insert(orgaUri, values);
			//setOrganization(uri.getPathSegments().get(1), values);

			

//			Uri.Builder builder = ContactsContract.Contacts.CONTENT_URI.buildUpon();
			//builder = ContentUris.appendId(builder, contactId);
//			orgaUri = Uri.withAppendedPath(builder.build(), ContactsContract.Contacts.Data.CONTENT_DIRECTORY);
			orgaUri = ContactsContract.Data.CONTENT_URI;

			values.clear();
			setStringValue(values, ContactsContract.CommonDataKinds.Organization.COMPANY, ContactColumns.OrganizationColumns.COMPANY, orga);
			setStringValue(values, ContactsContract.CommonDataKinds.Organization.LABEL, ContactColumns.OrganizationColumns.LABEL, orga);
			setIntValue(values, ContactsContract.CommonDataKinds.Organization.TYPE, ContactColumns.OrganizationColumns.TYPE, orga);

			//title needs special treatment. if we only set a company, contacts app won't show it at all (at least in 2.1)... title is a must!
			String value = orga.optString(ContactColumns.OrganizationColumns.TITLE);
			if (null == value || value.length() == 0) {
				value = "N/A";
			}
			values.put(ContactsContract.CommonDataKinds.Organization.TITLE, value);
			
			//this only works if contact and rawcontact have the same id!
			values.put(ContactsContract.CommonDataKinds.Organization.RAW_CONTACT_ID, contactId);
			values.put(ContactsContract.CommonDataKinds.Organization.MIMETYPE,	ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
			cr.insert(orgaUri, values);

		}
		organisations = null;
		
		
	    try {
	        cr.applyBatch(ContactsContract.AUTHORITY, operationList);
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
		
	}

    
	/**
	 * Create a new contact on the device given a JSON object
	 * 
	 * @param contact The contact to be created
	 * @throws JSONException when unable to decode the JSON elements
	 */
	private void store_contact(JSONObject contact) throws JSONException {
		
		ContentResolver cr = mParent.getContentResolver();
		
		/*
		 * Store base values
		 */
		ContentValues values = new ContentValues();
		setStringValue(values, People._ID, ContactColumns.ID, contact);

		//handle name differently
		String name = contact.optString(ContactColumns.NAME);
		String givenName = null;
		String familyName = null;
		if (null != name && name.length() > 0 && name.contains(",")) {
			String[] nameParts = name.split(",");
			if (nameParts.length == 2) {
				givenName = nameParts[1].trim();
				familyName = nameParts[0].trim();
				name = nameParts[1].trim() + " " + nameParts[0].trim();
			} else {
				name = name;
			}
		}
		values.put(People.NAME, name);
		
		//setStringValue(values, People.NAME, ContactColumns.NAME, contact);
		
		setStringValue(values, People.TIMES_CONTACTED, ContactColumns.TIMES_CONTACTED, contact);
		setIntValue(values, People.STARRED, ContactColumns.STARRED, contact);
		
		setStringValue(values, People.NOTES, ContactColumns.NOTES, contact);
		setStringValue(values, People.CUSTOM_RINGTONE, ContactColumns.CUSTOM_RING_TONE, contact);
		setLongValue(values, People.LAST_TIME_CONTACTED, ContactColumns.LAST_TIME_CONTACTED, contact);
		setStringValue(values, People.PHONETIC_NAME, ContactColumns.PHONETIC_NAME, contact);
		setStringValue(values, People.PHOTO_VERSION, ContactColumns.PHOTO_VERSION, contact);
		setIntValue(values, People.SEND_TO_VOICEMAIL, ContactColumns.SEND_TO_VOICEMAIL, contact);

		
		
		//Uri uri = createPersonInMyContactsGroup(cr, values);
		Uri uri = cr.insert(People.CONTENT_URI, values);
		long contactId = ContentUris.parseId(uri);
		
		if ( uri == null) {
			System.err.println("Failed to create contact for " + contact.getString( ContactColumns.NAME ));
			return;
		}
		
//		//special treatment for display name as it is ignored when set at insert time
//		values.clear();
//		setStringValue(values, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, ContactColumns.DISPLAY_NAME, contact);
//		if (givenName != null && givenName.length() > 0) {
//			values.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName);
//		}
//		if (familyName != null && familyName.length() > 0) {
//			values.put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName);
//		}
//		
//		String where = ContactsContract.Data.MIMETYPE + " = ? AND "
//			+ ContactsContract.CommonDataKinds.StructuredName.CONTACT_ID
//			+ " = ?";
//	
//		String[] whereArgs = new String[] {
//			ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
//			Long.toString(contactId) };
//		cr.update(ContactsContract.Data.CONTENT_URI, values, where, whereArgs);
//		
		
		/*
		 * Store phone numbers
		 */
		JSONArray phones = contact.getJSONArray( ContactColumns.PHONE_NUMBERS );
		for( int i = 0; i < phones.length(); ++i ){
			JSONObject phone = phones.getJSONObject(i);
			if (phone == null ){
				continue;
			}
			
			Uri phoneUri = null;
			phoneUri = Uri.withAppendedPath(uri, People.Phones.CONTENT_DIRECTORY);
			values.clear();
			setIntValue(values, People.Phones.TYPE, ContactColumns.PhoneColumns.TYPE, phone);
			setStringValue(values, People.Phones.NUMBER, ContactColumns.PhoneColumns.NUMBER, phone);
			setBooleanValue(values, People.Phones.ISPRIMARY, ContactColumns.PhoneColumns.IS_PRIMARY, phone);
			setStringValue(values, People.Phones.LABEL, ContactColumns.PhoneColumns.LABEL, phone);
			setStringValue(values, People.Phones.NUMBER_KEY, ContactColumns.PhoneColumns.NUMBER_KEY, phone);
			
			cr.insert(phoneUri, values);
		}
		phones = null;
		
		/*
		 * Store photo
		 */
		JSONArray photos = contact.getJSONArray( ContactColumns.PHOTOS );
		if ( photos.length() > 0 ) {
			String photo = photos.getString(0);
			if (photo != null && !photo.equals("") ){
				try {
					Contacts.People.setPhotoData(cr, uri, Base64.decode(photo));
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		photos = null;
		
		/*
		 * Store addresses
		 */
		JSONArray addresses = contact.getJSONArray( ContactColumns.CONTACT_METHODS );
		for( int i = 0; i < addresses.length(); ++i ){
			JSONObject address = addresses.getJSONObject(i);
			if (address == null ){
				continue;
			}
			
			Uri addressUri = null;
			addressUri = Uri.withAppendedPath(uri, People.ContactMethods.CONTENT_DIRECTORY);
			values.clear();
			setStringValue(values, People.ContactMethods.KIND, ContactColumns.ContactMethodColumns.KIND, address);
			setStringValue(values, People.ContactMethods.DATA, ContactColumns.ContactMethodColumns.DATA, address);
			setIntValue(values, People.ContactMethods.TYPE, ContactColumns.ContactMethodColumns.TYPE, address);
			setBooleanValue(values, People.ContactMethods.ISPRIMARY, ContactColumns.ContactMethodColumns.IS_PRIMARY, address);

			setStringValue(values, People.ContactMethods.AUX_DATA, ContactColumns.ContactMethodColumns.AUX_DATA, address);
			setStringValue(values, People.ContactMethods.LABEL, ContactColumns.ContactMethodColumns.LABEL, address);
			
			cr.insert(addressUri, values);
		}
		addresses = null;
	
		
		/*
		 * Store organizations
		 */
		JSONArray organisations = contact.getJSONArray( ContactColumns.ORGANIZATIONS );
		for( int i = 0; i < organisations.length(); ++i ){
			JSONObject orga = organisations.getJSONObject(i);
			if (orga == null ){
				continue;
			}
			
			Uri orgaUri = null;
			//orgaUri = Uri.withAppendedPath(uri, Contacts.Organizations.CONTENT_DIRECTORY);
			
//			values.clear();
//			setStringValue(values, Contacts.Organizations.COMPANY, ContactColumns.OrganizationColumns.COMPANY, orga);
//			setBooleanValue(values, Contacts.Organizations.ISPRIMARY, ContactColumns.OrganizationColumns.IS_PRIMARY, orga);
//			setStringValue(values, Contacts.Organizations.LABEL, ContactColumns.OrganizationColumns.LABEL, orga);
//			setStringValue(values, Contacts.Organizations.TITLE, ContactColumns.OrganizationColumns.TITLE, orga);
//			setIntValue(values, Contacts.Organizations.TYPE, ContactColumns.OrganizationColumns.TYPE, orga);

			//cr.insert(orgaUri, values);
			//setOrganization(uri.getPathSegments().get(1), values);

			

//			Uri.Builder builder = ContactsContract.Contacts.CONTENT_URI.buildUpon();
			//builder = ContentUris.appendId(builder, contactId);
//			orgaUri = Uri.withAppendedPath(builder.build(), ContactsContract.Contacts.Data.CONTENT_DIRECTORY);
			orgaUri = ContactsContract.Data.CONTENT_URI;

			values.clear();
			setStringValue(values, ContactsContract.CommonDataKinds.Organization.COMPANY, ContactColumns.OrganizationColumns.COMPANY, orga);
			setStringValue(values, ContactsContract.CommonDataKinds.Organization.LABEL, ContactColumns.OrganizationColumns.LABEL, orga);
			setIntValue(values, ContactsContract.CommonDataKinds.Organization.TYPE, ContactColumns.OrganizationColumns.TYPE, orga);

			//title needs special treatment. if we only set a company, contacts app won't show it at all (at least in 2.1)... title is a must!
			String value = orga.optString(ContactColumns.OrganizationColumns.TITLE);
			if (null == value || value.length() == 0) {
				value = "N/A";
			}
			values.put(ContactsContract.CommonDataKinds.Organization.TITLE, value);
			
			//this only works if contact and rawcontact have the same id!
			values.put(ContactsContract.CommonDataKinds.Organization.RAW_CONTACT_ID, contactId);
			values.put(ContactsContract.CommonDataKinds.Organization.MIMETYPE,	ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE);
			cr.insert(orgaUri, values);

		}
		organisations = null;
		
		
	}
	
	private void setStringValue(ContentValues values, String contactConst, String columnConst, JSONObject source) {
		try {
			String value = source.getString(columnConst);
			values.put(contactConst, value);
		} catch (JSONException e) {	//ignore
		}
	}

	private void setIntValue(ContentValues values, String contactConst, String columnConst, JSONObject source) {
		try {
			int value = source.getInt(columnConst);
			values.put(contactConst, value);
		} catch (JSONException e) {	//ignore
		}
	}

	private void setLongValue(ContentValues values, String contactConst, String columnConst, JSONObject source) {
		try {
			long value = source.getLong(columnConst);
			values.put(contactConst, value);
		} catch (JSONException e) {	//ignore
		}
	}
	
	private void setBooleanValue(ContentValues values, String contactConst, String columnConst, JSONObject source) {
		try {
			int value = (source.getBoolean(columnConst ) ? 1 : 0);
			values.put(contactConst, value);
		} catch (JSONException e) {	//ignore
		}
	}
	
	private void showError( String message ){
		Message msg = mRestoreHandler.obtainMessage(JsonBackup.RESTORE_SHOW_ERROR);
		Bundle b = new Bundle();
		b.putString("message", message);
		msg.setData(b);
		mRestoreHandler.sendMessage(msg);
	}

	/**
	 * Finish up the current contact and exit
	 */
	public void finish() {
		mKeepRunning = false;
	}
}

