/*
Copyright (c) 2012, Apps4Av Inc. (apps4av.com) 
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
 *
 *     THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.ds.avare.webinfc;


import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.ds.avare.PlanActivity;
import com.ds.avare.R;
import com.ds.avare.StorageService;
import com.ds.avare.externalFlightPlan.ExternalFlightPlan;
import com.ds.avare.place.Airway;
import com.ds.avare.place.Destination;
import com.ds.avare.place.DestinationFactory;
import com.ds.avare.place.Plan;
import com.ds.avare.plan.LmfsInterface;
import com.ds.avare.plan.LmfsPlan;
import com.ds.avare.plan.LmfsPlanList;
import com.ds.avare.position.Coordinate;
import com.ds.avare.position.Projection;
import com.ds.avare.storage.Preferences;
import com.ds.avare.storage.StringPreference;
import com.ds.avare.utils.GenericCallback;
import com.ds.avare.utils.Helper;
import com.ds.avare.utils.NetworkHelper;
import com.ds.avare.utils.WeatherHelper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Observable;
import java.util.Observer;

/**
 * 
 * @author zkhan
 * This class feeds the WebView with data
 */
public class WebAppPlanInterface implements Observer {
    private StorageService mService; 
    private Preferences mPref;
    private WebView mWebView;
    private SearchTask mSearchTask;
    private CreateTask mCreateTask;
    private WeatherTask mWeatherTask;
    private Thread mWeatherThread;
    private LinkedHashMap<String, String> mSavedPlans;
    private GenericCallback mCallback;
    private Context mContext;
    private int mPlanIdx;
    private int mPlanCnt;
    private String mPlanFilter;
    private int mFilteredSize;
	private LmfsPlanList mFaaPlans;

	private static final int MSG_CLEAR_PLAN = 2;
    private static final int MSG_ADD_PLAN = 3;
    private static final int MSG_ADD_SEARCH = 4;
    private static final int MSG_TIMER = 5;
    private static final int MSG_CLEAR_PLAN_SAVE = 7;
    private static final int MSG_ADD_PLAN_SAVE = 8;
    private static final int MSG_NOTBUSY = 9;
    private static final int MSG_BUSY = 10;
    private static final int MSG_ACTIVE = 11;
    private static final int MSG_INACTIVE = 12;
    private static final int MSG_SAVE_HIDE = 14;
    private static final int MSG_ERROR = 15;
    private static final int MSG_PREV_HIDE = 16;
    private static final int MSG_NEXT_HIDE = 17;
    private static final int MSG_PLAN_COUNT = 18;
	private static final int MSG_UPDATE_WEATHER = 19;
	private static final int MSG_FILL_FORM = 120;
	private static final int MSG_FAA_PLANS = 121;
	private static final int MSG_SET_EMAIL = 122;

    private static final int MAX_PLANS_SHOWN = 5;
    
    /** 
     * Instantiate the interface and set the context
     */
    public WebAppPlanInterface(WebView ww, GenericCallback cb) {
        mWebView = ww;
        mCallback = cb;
        mService = StorageService.getInstance();
        mContext = mService.getApplicationContext();
        mPref = mService.getPreferences();
        mPlanIdx = 0;
        mPlanCnt = 0;
        mPlanFilter = "";
		// TODO: refactor in abstract plan management
		mSavedPlans = Plan.getAllPlans(mService.getDBResource().getUserPlans());
		setFilteredSize();
	}

	private String checkNull(String input) {
		if(input == null) {
			return "";
		}
		return input;
	}


	/**
	 * Fill plan form with data stored
	 */
	@JavascriptInterface
	public void fillPlan() {
		mHandler.sendEmptyMessage(MSG_BUSY);

		// Fill in from storage, this is going to be mostly reflecting the user's most
		// used settings in the form
		LmfsPlan pl = new LmfsPlan(mPref);
		Plan p = mService.getPlan();
		String destination = "";
		String departure = "";
		String flightDuration = "";
		String fuelOnBoard = "";
		String departureInstant = "";
		String route = "";

		// If plan has valid BASE origin and destinations, fill them in
		// Fill this LMFS form based on plan
		int num = p.getDestinationNumber();
		if(num >= 2) {
			if(p.getDestination(num - 1).getType().equals(Destination.BASE)) {
				destination = "K" + p.getDestination(num - 1).getID(); // USA only
			}
			if(p.getDestination(0).getType().equals(Destination.BASE)) {
				departure = "K" + p.getDestination(0).getID(); // USA only
			}
		}

		// find time remaining time based on true AS
		double time = 0;
		try {
			time = p.getDistance() / Double.parseDouble(pl.cruisingSpeed);
		}
		catch (Exception e) {
		}
		flightDuration = LmfsPlan.timeToDuration(time);
		fuelOnBoard = LmfsPlan.timeToDuration(Double.parseDouble(pl.fuelEndurance));

		// fill time to now()
		departureInstant = System.currentTimeMillis() + "";

		if(num > 2) {
			route = "";
			// Fill route
			for(int dest = 1; dest < (num - 1); dest++) {
				String type = p.getDestination(dest).getType();
				if(type.equals(Destination.GPS) || type.equals(Destination.MAPS) || type.equals(Destination.UDW)) {
					route += LmfsPlan.convertLocationToGpsCoords(p.getDestination(dest).getLocation()) + " ";
				}
				else if(type.equals(Destination.BASE)) {
					route += "K" + p.getDestination(dest).getID() + " "; // USA
				}
				else {
					route += p.getDestination(dest).getID() + " ";
				}
			}
		}
		if(route.equals("")) {
			route = LmfsPlan.DIRECT;
		}

		// Fill form
		Message m = mHandler.obtainMessage(MSG_FILL_FORM, (Object)(
				"'" +  checkNull(pl.aircraftId) + "'," +
						"'" +  checkNull(pl.flightRule)  + "'," +
						"'" +  checkNull(pl.flightType)  + "'," +
						"'" +  checkNull(pl.noOfAircraft)  + "'," +
						"'" +  checkNull(pl.aircraftType) + "'," +
						"'" +  checkNull(pl.wakeTurbulence) + "'," +
						"'" +  checkNull(pl.aircraftEquipment) + "'," +
						"'" +  checkNull(departure) + "'," +
						"'" +  checkNull(LmfsPlan.getTimeFromInstance(departureInstant)) + "'," +
						"'" +  checkNull(pl.cruisingSpeed) + "'," +
						"'" +  checkNull(String.format(Locale.US, "%03d", p.getAltitude() / 100)) + "'," +
						"'" +  checkNull(pl.surveillanceEquipment) + "'," +
						"'" +  checkNull(route) + "'," +
						"'" +  checkNull(pl.otherInfo) + "'," +
						"'" +  checkNull(destination) + "'," +
						"'" +  checkNull(LmfsPlan.durationToTime(flightDuration)) + "'," +
						"'" +  checkNull(pl.alternate1) + "'," +
						"'" +  checkNull(pl.alternate2) + "'," +
						"'" +  checkNull(LmfsPlan.durationToTime(fuelOnBoard)) + "'," +
						"'" +  checkNull(pl.peopleOnBoard) + "'," +
						"'" +  checkNull(pl.aircraftColor) + "'," +
						"'" +  checkNull(pl.supplementalRemarks) + "'," +
						"'" +  checkNull(pl.pilotInCommand) + "'," +
						"'" +  checkNull(pl.pilotInfo) + "'"
		));
		mHandler.sendMessage(m);
		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}

	/**
	 * Select the plan on the FAA list
	 */
	@JavascriptInterface
	public void moveToFaa(int index) {
		if(null == mFaaPlans) {
			return;
		}

		// refresh
		mHandler.sendEmptyMessage(MSG_BUSY);

		mFaaPlans.mSelectedIndex = index;
		mHandler.sendEmptyMessage(MSG_FAA_PLANS);

		mHandler.sendEmptyMessage(MSG_NOTBUSY);

	}


	/**
	 * Get briefing.
	 */
	@JavascriptInterface
	public void getWeatherFaa() {
		// refresh
		mHandler.sendEmptyMessage(MSG_BUSY);

		LmfsInterface infc = new LmfsInterface();
		if(null == mFaaPlans || null == mFaaPlans.getPlans() || mFaaPlans.mSelectedIndex >= mFaaPlans.getPlans().size()) {
			mHandler.sendEmptyMessage(MSG_NOTBUSY);
			return;
		}

		// Get plan, then request its briefing
		LmfsPlan pl = infc.getFlightPlan(mFaaPlans.getPlans().get(mFaaPlans.mSelectedIndex).getId());
		String err = infc.getError();
		if(null != err) {
			// failed to get plan
			mHandler.sendEmptyMessage(MSG_NOTBUSY);
			Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
			mHandler.sendMessage(m);
			return;
		}

		infc.getBriefing(pl, mPref.isWeatherTranslated(), LmfsPlan.ROUTE_WIDTH);
		err = infc.getError();
		if(null == err) {
			// success
			err = mContext.getString(R.string.Success);
		}
		Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
		mHandler.sendMessage(m);

		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}

	/**
	 * File an FAA plan and save it
	 */
	@JavascriptInterface
	public void filePlan(
			String aircraftId,
			String flightRule,
			String flightType,
			String noOfAircraft,
			String aircraftType,
			String wakeTurbulence,
			String aircraftEquipment,
			String departure,
			String departureDate,
			String cruisingSpeed,
			String level,
			String surveillanceEquipment,
			String route,
			String otherInfo,
			String destination,
			String totalElapsedTime,
			String alternate1,
			String alternate2,
			String fuelEndurance,
			String peopleOnBoard,
			String aircraftColor,
			String supplementalRemarks,
			String pilotInCommand,
			String pilotInfo) {

		mHandler.sendEmptyMessage(MSG_BUSY);
		LmfsPlan pl = new LmfsPlan();
		pl.aircraftId = aircraftId.toUpperCase(Locale.getDefault());
		pl.flightRule = flightRule.toUpperCase(Locale.getDefault());
		pl.flightType = flightType.toUpperCase(Locale.getDefault());
		pl.noOfAircraft = noOfAircraft.toUpperCase(Locale.getDefault());
		pl.aircraftType = aircraftType.toUpperCase(Locale.getDefault());
		pl.wakeTurbulence = wakeTurbulence.toUpperCase(Locale.getDefault());
		pl.aircraftEquipment = aircraftEquipment.toUpperCase(Locale.getDefault());
		pl.departure = departure.toUpperCase(Locale.getDefault());
		pl.departureDate = LmfsPlan.getInstanceFromTime(departureDate.toUpperCase(Locale.getDefault()));
		pl.cruisingSpeed = cruisingSpeed.toUpperCase(Locale.getDefault());
		pl.level = level.toUpperCase(Locale.getDefault());
		pl.surveillanceEquipment = surveillanceEquipment.toUpperCase(Locale.getDefault());
		pl.route = route.toUpperCase(Locale.getDefault());
		pl.otherInfo = otherInfo.toUpperCase(Locale.getDefault());
		pl.destination = destination.toUpperCase(Locale.getDefault());
		pl.totalElapsedTime = LmfsPlan.getDurationFromInput(totalElapsedTime.toUpperCase(Locale.getDefault()));
		pl.alternate1 = alternate1.toUpperCase(Locale.getDefault());
		pl.alternate2 = alternate2.toUpperCase(Locale.getDefault());
		pl.fuelEndurance = LmfsPlan.getDurationFromInput(fuelEndurance.toUpperCase(Locale.getDefault()));
		pl.peopleOnBoard = peopleOnBoard.toUpperCase(Locale.getDefault());
		pl.aircraftColor = aircraftColor.toUpperCase(Locale.getDefault());
		pl.supplementalRemarks = supplementalRemarks.toUpperCase(Locale.getDefault());
		pl.pilotInCommand = pilotInCommand.toUpperCase(Locale.getDefault());
		pl.pilotInfo = pilotInfo.toUpperCase(Locale.getDefault());

		// Now file and show error messages
		LmfsInterface infc = new LmfsInterface();
		infc.fileFlightPlan(pl);
		String err = infc.getError();
		if(null == err) {
			// success filing
			getPlans();
			return;
		}
		Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
		mHandler.sendMessage(m);

		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}

	/**
	 * Amend an FAA plan and save it
	 */
	@JavascriptInterface
	public void amendPlan(
			String aircraftId,
			String flightRule,
			String flightType,
			String noOfAircraft,
			String aircraftType,
			String wakeTurbulence,
			String aircraftEquipment,
			String departure,
			String departureDate,
			String cruisingSpeed,
			String level,
			String surveillanceEquipment,
			String route,
			String otherInfo,
			String destination,
			String totalElapsedTime,
			String alternate1,
			String alternate2,
			String fuelEndurance,
			String peopleOnBoard,
			String aircraftColor,
			String supplementalRemarks,
			String pilotInCommand,
			String pilotInfo) {

		if(null == mFaaPlans || null == mFaaPlans.getPlans() || mFaaPlans.mSelectedIndex >= mFaaPlans.getPlans().size()) {
			return;
		}

		LmfsPlan pl = mFaaPlans.getPlans().get(mFaaPlans.mSelectedIndex);
		if(pl.getId() == null) {
			return;
		}

		mHandler.sendEmptyMessage(MSG_BUSY);
		pl.aircraftId = aircraftId.toUpperCase(Locale.getDefault());
		pl.flightRule = flightRule.toUpperCase(Locale.getDefault());
		pl.flightType = flightType.toUpperCase(Locale.getDefault());
		pl.noOfAircraft = noOfAircraft.toUpperCase(Locale.getDefault());
		pl.aircraftType = aircraftType.toUpperCase(Locale.getDefault());
		pl.wakeTurbulence = wakeTurbulence.toUpperCase(Locale.getDefault());
		pl.aircraftEquipment = aircraftEquipment.toUpperCase(Locale.getDefault());
		pl.departure = departure.toUpperCase(Locale.getDefault());
		pl.departureDate = LmfsPlan.getInstanceFromTime(departureDate.toUpperCase(Locale.getDefault()));
		pl.cruisingSpeed = cruisingSpeed.toUpperCase(Locale.getDefault());
		pl.level = level.toUpperCase(Locale.getDefault());
		pl.surveillanceEquipment = surveillanceEquipment.toUpperCase(Locale.getDefault());
		pl.route = route.toUpperCase(Locale.getDefault());
		pl.otherInfo = otherInfo.toUpperCase(Locale.getDefault());
		pl.destination = destination.toUpperCase(Locale.getDefault());
		pl.totalElapsedTime = LmfsPlan.getDurationFromInput(totalElapsedTime.toUpperCase(Locale.getDefault()));
		pl.alternate1 = alternate1.toUpperCase(Locale.getDefault());
		pl.alternate2 = alternate2.toUpperCase(Locale.getDefault());
		pl.fuelEndurance = LmfsPlan.getDurationFromInput(fuelEndurance.toUpperCase(Locale.getDefault()));
		pl.peopleOnBoard = peopleOnBoard.toUpperCase(Locale.getDefault());
		pl.aircraftColor = aircraftColor.toUpperCase(Locale.getDefault());
		pl.supplementalRemarks = supplementalRemarks.toUpperCase(Locale.getDefault());
		pl.pilotInCommand = pilotInCommand.toUpperCase(Locale.getDefault());
		pl.pilotInfo = pilotInfo.toUpperCase(Locale.getDefault());

		// Now file and show error messages
		LmfsInterface infc = new LmfsInterface();
		infc.amendFlightPlan(pl);
		String err = infc.getError();
		if(null == err) {
			// success filing
			getPlans();
			return;
		}
		Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
		mHandler.sendMessage(m);

		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}


	/**
	 * Close, open plan at FAA
	 */
	@JavascriptInterface
	public void planChangeState(String action, String arg) {
		if(null == mFaaPlans || null == mFaaPlans.getPlans() || mFaaPlans.mSelectedIndex >= mFaaPlans.getPlans().size()) {
			return;
		}

		/*
		 * Do the action of the plan
		 */
		LmfsInterface infc = new LmfsInterface();

		String err = null;
		String id = mFaaPlans.getPlans().get(mFaaPlans.mSelectedIndex).getId();
		String ver = mFaaPlans.getPlans().get(mFaaPlans.mSelectedIndex).versionStamp;
		if(id == null) {
			return;
		}
		mHandler.sendEmptyMessage(MSG_BUSY);
        switch (action) {
            case "Activate" ->
                // Activate plan with given ID
                    infc.activateFlightPlan(id, ver, arg);
            case "Close" ->
                // Activate plan with given ID
                    infc.closeFlightPlan(id, arg);
            case "Cancel" ->
                // Activate plan with given ID
                    infc.cancelFlightPlan(id);
        }
		err = infc.getError();
		mHandler.sendEmptyMessage(MSG_NOTBUSY);
		if(null == err) {
			// success changing, update state
			getPlans();
			return;
		}

		Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
		mHandler.sendMessage(m);
	}


	/**
	 * Get a list of FAA plans
	 */
	@JavascriptInterface
	public void getPlans() {
		mHandler.sendEmptyMessage(MSG_BUSY);

		LmfsInterface infc = new LmfsInterface();

		mFaaPlans = infc.getFlightPlans();
		String err = infc.getError();
		if(null == err) {
			// success filing
			err = mContext.getString(R.string.Success);
		}

		Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
		mHandler.sendMessage(m);

		mHandler.sendEmptyMessage(MSG_FAA_PLANS);

		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}


	/**
	 * Get an FAA plan, and fill the form
	 */
	@JavascriptInterface
	public void loadPlan() {

		if(null == mFaaPlans || null == mFaaPlans.getPlans() || mFaaPlans.mSelectedIndex >= mFaaPlans.getPlans().size()) {
			return;
		}

		mHandler.sendEmptyMessage(MSG_BUSY);

		LmfsInterface infc = new LmfsInterface();

		// Get plan in fill in the form
		LmfsPlan pl = infc.getFlightPlan(mFaaPlans.getPlans().get(mFaaPlans.mSelectedIndex).getId());
		String err = infc.getError();
		if(null != err) {
			// failed to get plan
			mHandler.sendEmptyMessage(MSG_NOTBUSY);
			Message m = mHandler.obtainMessage(MSG_ERROR, (Object)err);
			mHandler.sendMessage(m);
			return;
		}

		// Fill form
		Message m = mHandler.obtainMessage(MSG_FILL_FORM, (Object)(
				"'" +  checkNull(pl.aircraftId) + "'," +
						"'" +  checkNull(pl.flightRule)  + "'," +
						"'" +  checkNull(pl.flightType)  + "'," +
						"'" +  checkNull(pl.noOfAircraft)  + "'," +
						"'" +  checkNull(pl.aircraftType) + "'," +
						"'" +  checkNull(pl.wakeTurbulence) + "'," +
						"'" +  checkNull(pl.aircraftEquipment) + "'," +
						"'" +  checkNull(pl.departure) + "'," +
						"'" +  checkNull(LmfsPlan.getTimeFromInstance(pl.departureDate)) + "'," +
						"'" +  checkNull(pl.cruisingSpeed) + "'," +
						"'" +  checkNull(pl.level) + "'," +
						"'" +  checkNull(pl.surveillanceEquipment) + "'," +
						"'" +  checkNull(pl.route) + "'," +
						"'" +  Helper.formatJsArgs(checkNull(pl.otherInfo)) + "'," +
						"'" +  checkNull(pl.destination) + "'," +
						"'" +  checkNull(LmfsPlan.durationToTime(pl.totalElapsedTime)) + "'," +
						"'" +  checkNull(pl.alternate1) + "'," +
						"'" +  checkNull(pl.alternate2) + "'," +
						"'" +  checkNull(LmfsPlan.durationToTime(pl.fuelEndurance)) + "'," +
						"'" +  checkNull(pl.peopleOnBoard) + "'," +
						"'" +  checkNull(pl.aircraftColor) + "'," +
						"'" +  Helper.formatJsArgs(checkNull(pl.supplementalRemarks)) + "'," +
						"'" +  Helper.formatJsArgs(checkNull(pl.pilotInCommand)) + "'," +
						"'" +  Helper.formatJsArgs(checkNull(pl.pilotInfo)) + "'"
		));
		mHandler.sendMessage(m);
		mHandler.sendEmptyMessage(MSG_NOTBUSY);
	}

	/**
	 *
	 */
	public void setEmail() {
		// Set email in page for user to know where to register with
		mHandler.sendEmptyMessage(MSG_SET_EMAIL);
	}


    /**
     * 
     */
    public void timer() {
		Plan plan = mService.getPlan();

    	// If we are in sim mode, then send a message
	    if(mPref.isSimulationMode()) {
	    	mHandler.sendEmptyMessage(MSG_TIMER);
	    }
	    
	    // Also update active state
	    if(plan.isActive()) {
	    	mHandler.sendEmptyMessage(MSG_ACTIVE);	    	
	    	// Set destination next if plan active only
			if(plan.getDestination(plan.findNextNotPassed()) != null) {
				mService.setDestinationPlanNoChange(plan.getDestination(plan.findNextNotPassed()));
			}
	    }
	    else {
	    	mHandler.sendEmptyMessage(MSG_INACTIVE);  	
	    }
    }
    
    /**
     * 
     */
    public void clearPlan() {
    	mHandler.sendEmptyMessage(MSG_CLEAR_PLAN);
    }

    /**
     * 
     */
    public void clearPlanSave() {
    	mHandler.sendEmptyMessage(MSG_CLEAR_PLAN_SAVE);
    }

    /**
     * 
     * @param id
     * @param type
     */
    public void addWaypointToPlan(String id, String type, String subtype) {
    	// Add using javascript to show on page, strings require '' around them
    	Message m = mHandler.obtainMessage(MSG_ADD_PLAN, (Object)("'" + Helper.formatJsArgs(id) + "','" + Helper.formatJsArgs(type) + "','" + Helper.formatJsArgs(subtype) + "'"));
    	mHandler.sendMessage(m);
    }

    /**
     * New saved plan list when the plan save list changes.
     */
    @SuppressLint("DefaultLocale")
	public void newSavePlan() {
    	
    	// Turn on the spinny thing to tell user we are thinking
    	mHandler.sendEmptyMessage(MSG_BUSY);

    	// Clear out what we are showing
    	clearPlanSave();
    	
    	// Get the next clump of plans
    	ArrayList<String> planNames = getPlanNames(MAX_PLANS_SHOWN);
    	
    	// Remember how many were returned for display
    	mPlanCnt = planNames.size();
    	
    	// For each name we were given, add it to the list
    	for(String planName : planNames) {
        	Message m = mHandler.obtainMessage(MSG_ADD_PLAN_SAVE, (Object)("'" + Helper.formatJsArgs(planName) + "'"));
        	mHandler.sendMessage(m);
        }
    	
    	// Tell the plan list table to show the plans
    	Message m = mHandler.obtainMessage(MSG_SAVE_HIDE, (Object)("true"));
    	mHandler.sendMessage(m);

    	// Set the state of the "PREV" list button, hide if we are in the first page
    	m = mHandler.obtainMessage(MSG_PREV_HIDE, (Object)(mPlanIdx == 0 ? "true" : "false"));
    	mHandler.sendMessage(m);

    	// Set the state of the "NEXT" list button, hide if we are on the last page
    	m = mHandler.obtainMessage(MSG_NEXT_HIDE, (Object)(((mPlanIdx + mPlanCnt) == mFilteredSize) ? "true" : "false"));
    	mHandler.sendMessage(m);
    	
    	// A text string showing what plans are being displayed
    	String planCount = String.format("%d - %d of %d",  mFilteredSize == 0 ? 0 : mPlanIdx + 1, mPlanIdx + mPlanCnt, mFilteredSize);
    	m = mHandler.obtainMessage(MSG_PLAN_COUNT, (Object)("'" + planCount + "'"));
    	mHandler.sendMessage(m);

    	// We're done updating. Turn off the spinny gizmo
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /***
     * Build a collection of plans according to a starting
     * index, quantity and name filter
     * @param dispQty How many plans to fetch
     * @return Collection of plans in size zero thur dispQty
     */
    public ArrayList<String> getPlanNames(int dispQty) {
    	
    	//Init some local variables we will be using
    	int planIdx = 0;	// Used to count where we are in the plan list
    	ArrayList<String> planNames = new ArrayList<String>();
    	Iterator<String> it = mSavedPlans.keySet().iterator();

    	// As long as we have items in the list and need items for display
    	while(it.hasNext() && dispQty > 0) {
    		
    		// Get the plan name from the iterator
    		String planName = it.next();
    		
    		// Does this name qualify for display ?
    		if(true == containsIgnoreCase(planName, mPlanFilter)) {
    			
	    		// Is our walking index passed our current display index ?
	    		if(++planIdx > mPlanIdx) {
	    			
	    			// Add the name to the collection for display
	    			planNames.add(planName);
	    			
	    			// Adjust our displayed item counter
		        	dispQty--;
	    		}
    		}
        }

    	// Our collection is complete
    	return planNames;
    }

    @JavascriptInterface
    public void nextPage() {
    	mPlanIdx += MAX_PLANS_SHOWN;
    	newSavePlan();
    }

    @JavascriptInterface
    public void prevPage() {
    	mPlanIdx -= MAX_PLANS_SHOWN;
    	newSavePlan();
    }

    @JavascriptInterface
    public void firstPage() {
    	mPlanIdx = 0;
    	newSavePlan();
    }

    @JavascriptInterface
    public void lastPage() {
    	mPlanIdx = (int)((mFilteredSize - 1) / MAX_PLANS_SHOWN) * MAX_PLANS_SHOWN;
    	newSavePlan();
    }
    
    @JavascriptInterface
    public void refreshPlanList() {
		mHandler.sendEmptyMessage(MSG_BUSY);
    	mService.getExternalPlanMgr().forceReload();
		mSavedPlans = Plan.getAllPlans(mService.getDBResource().getUserPlans());
		setFilteredSize();
    	newSavePlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }
    
    /**
     * New plan when the plan changes.
     */
    public void newPlan() {
		clearPlan();
        Plan plan = mService.getPlan();
        int num = plan.getDestinationNumber();
        for(int dest = 0; dest < num; dest++) {
        	Destination d = plan.getDestination(dest);
        	addWaypointToPlan(d.getID(), d.getType(), d.getFacilityName());
        }
    }

    /*
     * To discard same location, see if they are close
     */
    private boolean isSame(Location l0, Location l1) {
    	double dist = Projection.getStaticDistance(l0.getLongitude(), l0.getLatitude(), l1.getLongitude(), l1.getLatitude());

    	return dist < 0.01;
	}
    
    /**
     * New dest
     * @param arg0
     * @param arg1
     */
	@Override
	public void update(Observable arg0, Object arg1) {
		/*
		 * Add to Plan that we found from add action
		 */
		Destination d = (Destination)arg0;
		int num = mService.getPlan().getDestinationNumber();
		// Make sure duplicates do not appear. This can happen with airways
		if(num > 0) {
			Destination prev = mService.getPlan().getDestination(num - 1);
			if(isSame(prev.getLocation(), d.getLocation())) {
				if(prev.getType().equals(Destination.GPS) && (!d.getType().equals(Destination.GPS))) {
					// Remove last one
					mService.getPlan().remove(num - 1);
				}
				else {
					// No need to add this
					return;
				}
			}
		}
		mService.getPlan().appendDestination(d);
		addWaypointToPlan(d.getID(), d.getType(), d.getFacilityName());
	}
	
    /**
     * Move an entry in the plan
     */
    @JavascriptInterface
    public void moveUp() {
    	// surround JS each call with busy indication / not busy 
    	Plan plan = mService.getPlan();
    	// move active point up
    	int next = plan.findNextNotPassed();
    	if(next == 0) {
    		// cannot do already at top
    		return;
    	}
    	plan.move(next, next - 1);
    	plan.regress(); // move with the point
    	mHandler.sendEmptyMessage(MSG_BUSY);
    	newPlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * Move an entry in the plan
     */
    @JavascriptInterface
    public void moveDown() {
    	// surround JS each call with busy indication / not busy 
		// move active point down
    	Plan plan = mService.getPlan();
    	
    	int next = plan.findNextNotPassed();
    	if(next == (plan.getDestinationNumber() - 1)) {
    		// cannot do already at bottom
    		return;
    	}

    	plan.move(next, next + 1);
    	plan.advance();
    	
    	mHandler.sendEmptyMessage(MSG_BUSY);

    	newPlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * 
     */
    @JavascriptInterface
    public void discardPlan() {
    	mHandler.sendEmptyMessage(MSG_BUSY);

    	mService.newPlan();
    	mService.getPlan().setName("");
    	newPlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }


    /**
     * Activate/Deactivate the plan	
     */
    @JavascriptInterface
    public void activateToggle() {
    	Plan plan = mService.getPlan();
    	if(plan.isActive()) {
    		plan.makeInactive();
	    	mHandler.sendEmptyMessage(MSG_INACTIVE);
    		mService.setDestination(null);
    	}
    	else {
    		plan.makeActive(mService.getGpsParams());    		
	    	mHandler.sendEmptyMessage(MSG_ACTIVE);
    		if(plan.getDestination(plan.findNextNotPassed()) != null) {
    			mService.setDestinationPlanNoChange(plan.getDestination(plan.findNextNotPassed()));
    		}
    	}
    }

    /**
     * 
     * @param
     */
    @JavascriptInterface
    public void deleteWaypoint() {
		mHandler.sendEmptyMessage(MSG_BUSY);
    	// Delete the one that has a mark on it, or the active waypoint
    	mService.getPlan().remove(mService.getPlan().findNextNotPassed());
    	newPlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }


	/**
	 *
	 */
	@JavascriptInterface
	public String nextWaypointID() {
		mHandler.sendEmptyMessage(MSG_BUSY);

		// Return the identifier of the active waypoint
		Plan plan = mService.getPlan();
		Destination nextDest = plan.getDestination(plan.findNextNotPassed());

		mHandler.sendEmptyMessage(MSG_NOTBUSY);

		return nextDest.getID();
	}


	/**
     * 
     */
    @JavascriptInterface
    public void moveBack() {
		mHandler.sendEmptyMessage(MSG_BUSY);

    	mService.getPlan().regress();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * Move the pointer to a particular location
     */
    @JavascriptInterface
    public void moveTo(int index) {
		mService.getPlan().moveTo(index);
    }


    /**
     * 
     */
    @JavascriptInterface
    public void moveForward() {
		mHandler.sendEmptyMessage(MSG_BUSY);

    	mService.getPlan().advance();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * 
     * @param id
     * @param type
     */	
    @JavascriptInterface
    public void addToPlan(String id, String type, String subtype) {
		/*
    	 * Add from JS search query
    	 */
    	mHandler.sendEmptyMessage(MSG_BUSY);

    	Destination d = DestinationFactory.build(id, type);
    	d.addObserver(this);
    	d.find(subtype);
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * 
     * @param name
     */
    @JavascriptInterface
    public void savePlan(String name) {
		Plan plan = mService.getPlan();
    	if(plan.getDestinationNumber() < 2) {
    		// Anything less than 2 is not a plan
    		return;
    	}
    	mHandler.sendEmptyMessage(MSG_BUSY);
    	
    	// TODO: hook in abstract plan management
    	plan.setName(name);
    	String format = plan.putPlanToStorageFormat();
    	mSavedPlans.put(name, format);
    	mService.getDBResource().setUserPlans(Plan.putAllPlans(mSavedPlans));
    	setFilteredSize();
    	
    	newSavePlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * 
     * @param name
     */
    @JavascriptInterface
    public void loadPlan(String name) {
		// surround JS each call with busy indication / not busy
    	mHandler.sendEmptyMessage(MSG_BUSY);

    	// If we have an active plan, we need to turn it off now since we are
    	// loading a new one.
    	Plan plan = mService.getPlan();
    	if(null != plan) {
    		plan.makeInactive();
    		
    		// If it is an external plan, tell it to unload
    		ExternalFlightPlan efp = mService.getExternalPlanMgr().get(plan.getName());
    		if(null != efp) {
    			efp.unload(mService);
    		}
    	}

    	// Clear out any destination that may have been set.
		mService.setDestination(null);

		// If this is defined as an external flight plan, then tell it we 
		// are loading into memory.
		ExternalFlightPlan efp = mService.getExternalPlanMgr().get(name);
		if(null != efp) {
			efp.load(mService);
		}
		
    	mService.newPlanFromStorage(mSavedPlans.get(name), false);
    	mService.getPlan().setName(name);
    	newPlan();
    	
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /**
     * 
     * @param
     */
    @JavascriptInterface
    public void loadPlanReverse(String name) {
		mHandler.sendEmptyMessage(MSG_BUSY);

    	mService.newPlanFromStorage(mSavedPlans.get(name), true);
    	mService.getPlan().setName(name);
    	newPlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

	/**
	 * Set altitude of plan from HTML
	 * @param altitude
	 */
	@JavascriptInterface
	public void setAltitude(String altitude) {
		mService.getPlan().setAltitude(Integer.parseInt(altitude) * 100);
	}

	/**
	 * Called from the javascript page when we need to build a new
	 * list of flight plans that are screened by the indicated filter
	 *
	 * @param planFilter flight plan must contain this string
	 */
    @JavascriptInterface
    public void planFilter(String planFilter) {
    	
    	// Save off what the filter string is 
    	mPlanFilter = ((planFilter == null) ? "" : planFilter);
 
    	// Set our display index back to the start
    	mPlanIdx = 0;
    	
    	// Figure out the filtered plan list size
    	setFilteredSize();
    	
    	// re-build the display list
    	newSavePlan();
    }

    /**
     * Walk the list of known plans and apply the filter to get a count
     * of how many plans match the filter
     */
    private void setFilteredSize() {
    	// re-calc the size of our plan list based upon the filter
    	mFilteredSize = 0;
		Iterator<String> it = mSavedPlans.keySet().iterator();
    	while(true == it.hasNext()){
    		String planName = it.next();
    		if(true == containsIgnoreCase(planName, mPlanFilter)) {
    			mFilteredSize++;
    		}
    	}
    }
    
    @SuppressLint("DefaultLocale")
	private boolean containsIgnoreCase(String str1, String str2) {
    	str1 = str1.toLowerCase();
    	str2 = str2.toLowerCase();
    	return str1.contains(str2);
    }
    /**
     * 
     * @param
     */
    @JavascriptInterface
    public void saveDelete(String name) {
		mHandler.sendEmptyMessage(MSG_BUSY);

    	// If we have a plan that is active, and it is the plan
    	// we are attempting to delete, then make it inactive
    	Plan plan = mService.getPlan();
    	if(null != plan) {
    		if(null != plan.getName()) {
	    		if(true == plan.getName().equalsIgnoreCase(name)) {
	        		if(true == plan.isActive()) {
		    			plan.makeInactive();
	        		}
	    		}
    		}
    	}
    	
    	// Remove the plan from our internal list of names
    	mSavedPlans.remove(name);

    	// Now remove the plan from storage
    	// TODO: all plans should be a single abstraction
    	if(true == mService.getExternalPlanMgr().isExternal(name)) {
    		mService.getExternalPlanMgr().delete(name);
    	} else {
			mService.getDBResource().deleteUserPlan(name);
    	}
    	
    	setFilteredSize();
	    newSavePlan();
    	mHandler.sendEmptyMessage(MSG_NOTBUSY);
    }

    /** 
     * Search for some place
     */
    @JavascriptInterface
    public void search(String value) {
        
    	/*
         * If text is 0 length or too long, then do not search, show last list
         */
        if(0 == value.length()) {
            return;
        }
        
        if(null != mSearchTask) {
            if (!mSearchTask.getStatus().equals(AsyncTask.Status.FINISHED)) {
                /*
                 * Cancel the last query
                 */
                mSearchTask.cancel(true);
            }
        }

    	mHandler.sendEmptyMessage(MSG_BUSY);
        mSearchTask = new SearchTask();
        mSearchTask.execute(value);
    }

    /** 
     * JS polls every second to get all plan data.
     */
    @JavascriptInterface
    public String getPlanData() {
    	Plan plan = mService.getPlan();
    	
        /*
         * Now update HTML with latest plan stuff, do this every time we start the Plan screen as 
         * things might have changed.
         */
    	int passed = plan.findNextNotPassed();
    	int numDest = plan.getDestinationNumber();
    	// add plan name upfront
    	String name = plan.getName();
    	if(name == null) {
    		name = "";
    	}
    	String plans = name + "::::";
    	
    	// make a :: separated plan list, then add totals to it
    	for(int num = 0; num < numDest; num++) {
    		Destination d = plan.getDestination(num);
    		plans += (passed == num ? 1 : 0) + "," +
                    Math.round(Helper.getMagneticHeading(d.getBearing(), d.getDeclination())) + "," +
                    Math.round(Helper.getMagneticHeading(d.getBearing() + d.getWCA(), d.getDeclination())) + "," +
                    Math.round(d.getDistance()) + "," +
                    Math.round(d.getGroundSpeed()) + "," +
                    d.getEte() +  "," +
                    d.getID() + "," +
                    d.getDbType() + "," +
                    d.getFuel() + "," +
                    d.getWinds() + "," +
					d.getAltitude() +
                    "::::";
    	}
    	// add total
    	plans += plan.toString();
    	
    	return plans;
    }

    /** 
     * refresh a plan on change from other screens
     */
    @JavascriptInterface
    public void refreshPlan() {
    	clearPlan();
    	newPlan();    	
    }

    /** 
     * Get weather form Internet
     */
    @JavascriptInterface
    public void getWeather() {
        if(null != mWeatherTask && mWeatherTask.running) {
        	return;
        }

        mWeatherTask = new WeatherTask();
        mWeatherTask.running = true;
        mWeatherThread = new Thread(mWeatherTask);
        mWeatherThread.start();
    }


	/**
     * Create a plan, guessing the types
     */
    @JavascriptInterface
    public void createPlan(String value) {
        
    	/*
         * If text is 0 length or too long, then do not search, show last list
         */
        if(0 == value.length()) {
            return;
        }

        if(null != mCreateTask) {
            if (!mCreateTask.getStatus().equals(AsyncTask.Status.FINISHED)) {
                /*
                 * Cancel the last query
                 */
                mCreateTask.cancel(true);
            }
        }

    	mHandler.sendEmptyMessage(MSG_BUSY);
        mCreateTask = new CreateTask();
        mCreateTask.execute(value);
    }

    /**
     * @author zkhan
     *
     */
    private class CreateTask extends AsyncTask<Object, Void, Boolean> {

    	LinkedList<String> selection = new LinkedList<String>();

        /* (non-Javadoc)
         * @see android.os.AsyncTask#doInBackground(Params[])
         */
        @Override
        protected Boolean doInBackground(Object... vals) {

            Thread.currentThread().setName("Create");

            String[] srch = ((String)vals[0]).toUpperCase(Locale.US).split(" ");
            
            /*
             * Here we guess types since we do not have user select
             */

            for(int num = 0; num < srch.length; num++) {
	            /*
	             * This is a geo coordinate?
	             */
				if(Helper.isGPSCoordinate(srch[num])) {
	            	String found = (new StringPreference(Destination.GPS, Destination.GPS, Destination.GPS, srch[num])).getHashedName();
	            	selection.add(found);
	            	continue;
	            }

	            /*
	             * Search from database. Make this a simple one off search
	             */
	            StringPreference s = mService.getDBResource().searchOne(srch[num]);
	            if(s != null) {
	            	String found = s.getHashedName();
	            	selection.add(found);
	            }
	            else if(num > 0 && num < (srch.length - 1)) {
	            	// Federal airway? Must start and end at some point
	            	LinkedList<String> ret = Airway.find(mService, srch[num - 1], srch[num], srch[num + 1]);
	            	if(ret != null) {
	            		// Found airway, insert. Airway is always a sequence of GPS points.
	            		selection.addAll(ret);
	            	}
					else {
						return false;
					}
	            }
				else {
					return false;
				}
            }
            return true;
        }
        
        /* (non-Javadoc)
         * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
         */
        @Override
        protected void onPostExecute(Boolean result) {
            /*
             * Set new search adapter
             */

            if(null == selection || false == result) {
            	mHandler.sendEmptyMessage(MSG_NOTBUSY);
				Message m = mHandler.obtainMessage(MSG_ERROR, (Object)mContext.getString(R.string.InvalidPoint));
				mHandler.sendMessage(m);
                return;
            }
            
            /*
             * Add each to the plan search
             */
            for (String val : selection) {
	            String id = StringPreference.parseHashedNameId(val);
	            String type = StringPreference.parseHashedNameDestType(val);
	            String dbtype = StringPreference.parseHashedNameDbType(val);

	        	/*
	        	 * Add each
	        	 */
	        	Destination d = DestinationFactory.build(id, type);
	        	d.addObserver(WebAppPlanInterface.this);
	        	d.find(dbtype);
            }
        	mHandler.sendEmptyMessage(MSG_NOTBUSY);
        }
    }


    /**
     * @author zkhan
     *
     */
    private class SearchTask extends AsyncTask<Object, Void, Boolean> {

    	String[] selection = null;

        /* (non-Javadoc)
         * @see android.os.AsyncTask#doInBackground(Params[])
         */
        @Override
        protected Boolean doInBackground(Object... vals) {

            Thread.currentThread().setName("Search");

            String srch = ((String)vals[0]).toUpperCase(Locale.US);

            /*
             * This is a geo coordinate?
             */
            if(Helper.isGPSCoordinate(srch)) {

            	selection = new String[1];
            	selection[0] = (new StringPreference(Destination.GPS, Destination.GPS, Destination.GPS, srch)).getHashedName();
                return true;
            }
            
            LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();

            mService.getDBResource().search(srch, params, true);
			mService.getUDWMgr().search(srch, params);			// From user defined points of interest
			StringPreference s = mService.getDBResource().getUserRecent(srch);
			if (null != s) {
				s.putInHash(params);
			}
            if(params.size() > 0) {
                selection = new String[params.size()];
                int iterator = 0;
                for(String key : params.keySet()){
                    selection[iterator] = StringPreference.getHashedName(params.get(key), key);
                    iterator++;
                }
            }
            return true;
        }
        
        /* (non-Javadoc)
         * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
         */
        @Override
        protected void onPostExecute(Boolean result) {
            /*
             * Set new search adapter
             */

            if(null == selection || false == result) {
            	mHandler.sendEmptyMessage(MSG_NOTBUSY);
                return;
            }
            
            /*
             * Add each to the plan search
             */
            for (int num = 0; num < selection.length; num++) {
            	String val = selection[num];
            	
	            String id = StringPreference.parseHashedNameId(val);
	            String name = StringPreference.parseHashedNameFacilityName(val);
	            String type = StringPreference.parseHashedNameDestType(val);
	            String dbtype = StringPreference.parseHashedNameDbType(val);

	        	Message m = mHandler.obtainMessage(MSG_ADD_SEARCH, (Object)("'" + Helper.formatJsArgs(id) + "','" + Helper.formatJsArgs(name) + "','" + Helper.formatJsArgs(type) + "','" + Helper.formatJsArgs(dbtype) + "'"));
	        	mHandler.sendMessage(m);
            }
        	mHandler.sendEmptyMessage(MSG_NOTBUSY);
        }
    }


    /**
     * This leak warning is not an issue if we do not post delayed messages, which is true here.
     * Must use handler for functions called from JS, but for uniformity, call all JS from this handler
     */
    @SuppressLint("HandlerLeak")
	private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
        	if(MSG_CLEAR_PLAN == msg.what) {
        		mWebView.loadUrl("javascript:plan_clear()");
        	}
        	else if(MSG_ADD_PLAN == msg.what) {
            	String func = "javascript:plan_add(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
        	else if(MSG_ADD_SEARCH == msg.what) {
            	String func = "javascript:search_add(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
        	else if (MSG_TIMER == msg.what) {
				Plan plan = mService.getPlan();
        		plan.simulate();
        	}
        	else if(MSG_CLEAR_PLAN_SAVE == msg.what) {
            	String func = "javascript:save_clear()";
            	mWebView.loadUrl(func);
        	}
        	else if(MSG_ADD_PLAN_SAVE == msg.what) {
            	String func = "javascript:save_add(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
        	else if(MSG_NOTBUSY == msg.what) {
        		mCallback.callback((Object)PlanActivity.UNSHOW_BUSY, null);
        	}
        	else if(MSG_BUSY == msg.what) {
        		mCallback.callback((Object)PlanActivity.SHOW_BUSY, null);
        	}
        	else if(MSG_ACTIVE == msg.what) {
        		mCallback.callback((Object)PlanActivity.ACTIVE, null);
        	}
        	else if(MSG_INACTIVE == msg.what) {
        		mCallback.callback((Object)PlanActivity.INACTIVE, null);
        	}
           	else if(MSG_SAVE_HIDE == msg.what) {	
            	String func = "javascript:save_hide(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
           	else if(MSG_PREV_HIDE == msg.what) {	
            	String func = "javascript:disable_prev(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
           	else if(MSG_NEXT_HIDE == msg.what) {	
            	String func = "javascript:disable_next(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
           	else if(MSG_PLAN_COUNT == msg.what) {	
            	String func = "javascript:set_plan_count(" + (String)msg.obj + ")";
            	mWebView.loadUrl(func);
        	}
			else if(MSG_UPDATE_WEATHER == msg.what) {
				String func = "javascript:update_weather(" + (String)msg.obj + ")";
				mWebView.loadUrl(func);
			}
			else if(MSG_ERROR == msg.what) {
        		mCallback.callback((Object)PlanActivity.MESSAGE, msg.obj);
        	}
			else if(MSG_FILL_FORM == msg.what) {
				String func = "javascript:plan_fill(" + (String)msg.obj + ")";
				mWebView.loadUrl(func);
			}
			else if(MSG_FAA_PLANS == msg.what) {
				/*
				 * Fill the table of plans
				 */
				if(mFaaPlans.getPlans() == null) {
					return;
				}
				String p = "";
				int i = 0;
				// Sent out plans as text separated by commas like selected,name,state
				for (LmfsPlan pl : mFaaPlans.getPlans()) {
					p += ((i == mFaaPlans.mSelectedIndex) ? "1" : "0") + "," + pl.departure + "-" + pl.destination + "-" + pl.aircraftId + "," + pl.currentState + ",";
					i++;
				}
				String func = "javascript:set_faa_plans('" + p + "')";
				mWebView.loadUrl(func);
			}
			else if(MSG_SET_EMAIL == msg.what) {
				String func = "javascript:set_email('" + mPref.getRegisteredEmail() + "')";
				mWebView.loadUrl(func);
			}


        }
    };

    /**
     * @author zkhan
     *
     */
    private class WeatherTask implements Runnable {

        private boolean running = true;

        /* (non-Javadoc)
         */
        @Override
        public void run() {

            Thread.currentThread().setName("Weather");

        	mHandler.sendEmptyMessage(MSG_BUSY);

            String Pirep = "";
            String Metar = "";
            String Taf = "";
            String notams = "";
			LinkedHashSet<String> ids = new LinkedHashSet<>();

			Plan p = mService.getPlan();
			// find points on this plan on greater circle
			Coordinate[] c = p.getCoordinates();
			// now find airports in the circle
			if(null != c) {
				for (Coordinate point : c) {
					mService.getDBResource().findAllAirports(point.getLongitude(), point.getLatitude(), 0.3f, ids);
				}

				String stations = "";
				if (ids.size() > 0) {
					String[] ida = new String[ids.size()];
					ids.toArray(ida);
					for (String s : ida) {
						// Do not add K to airports that have numeric in it
						if (s.matches("[A-Z]*")) {
							stations = stations + "K" + s + ",";
						} else {
							stations = stations + s + ",";
						}
					}
				}
				stations = stations.replaceAll(",$", "");

				/*
				 *  Get PIREP
				 */
				try {
					String out = NetworkHelper.getPIREPSPlan(c);
					String[] outm = out.split("::::");
					for (int i = 0; i < outm.length; i++) {
						outm[i] = WeatherHelper.formatPirepHTML(outm[i], mPref.isWeatherTranslated());
						Pirep += "<font size='5' color='white'>" + outm[i] + "<br></br>";
					}
				} catch (Exception e) {
					Pirep = mContext.getString(R.string.WeatherError);
				}

				if (!running) {
					return;
				}

				try {
					/*
					 *  Get TAFs
					 */
					if (!stations.equals("")) {
						String out = NetworkHelper.getTAFPlan(stations);
						String[] outm = out.split("::::");
						for (int i = 0; i < outm.length; i++) {
							String taf = WeatherHelper.formatWeatherHTML(outm[i], mPref.isWeatherTranslated());
							String[] vals = taf.split(" ");
							taf = WeatherHelper.formatVisibilityHTML(WeatherHelper.formatTafHTML(WeatherHelper.formatWindsHTML(WeatherHelper.formatWeatherHTML(taf.replace(vals[0], ""), mPref.isWeatherTranslated()), mPref.isWeatherTranslated()), mPref.isWeatherTranslated()));
							Taf += "<b><font size='5' color='white'>" + vals[0] + "</b><br>";
							Taf += "<font size='5' color='white'>" + taf + "<br></br>";
						}
					}
				} catch (Exception e) {
					Taf = mContext.getString(R.string.WeatherError);
				}

				if (!running) {
					return;
				}

				try {
					/*
					 *
					 */
					if (!stations.equals("")) {
						String out = NetworkHelper.getMETARPlan(stations);
						String[] outm = out.split("::::");
						for (int i = 0; i < outm.length; i++) {
							String[] vals = outm[i].split(",");
							String[] vals2 = vals[1].split(" ");
							String color = WeatherHelper.metarColorString(vals[0]);
							Metar += "<b><font size='5' + color='" + color + "'>" + vals2[0] + "</b><br>";
							Metar += "<font size='5'>" + WeatherHelper.addColorWithStroke(WeatherHelper.formatMetarHTML(vals[1].replace(vals2[0], ""), mPref.isWeatherTranslated()), color) + "<br></br>";
						}
					}
				} catch (Exception e) {
					Metar = mContext.getString(R.string.WeatherError);
				}

				if (!running) {
					return;
				}

				// NOTAMS
				int num = mService.getPlan().getDestinationNumber();
				String plann = "";
				for (int i = 0; i < num; i++) {
					Destination d = mService.getPlan().getDestination(i);
					if (d.getType().equals(Destination.BASE)) {
						if (d.getID().matches("[A-Z]*")) {
							plann += "K" + d.getID() + ",";
						} else {
							plann += d.getID() + ",";
						}
					}
				}
				if (!plann.equals("")) {
					plann = plann.replaceAll(",$", "");
					notams = NetworkHelper.getNotams(plann);
					if (notams == null) {
						notams = mContext.getString(R.string.NotamsError);
					}
				}
			}

			String plan = "";
			plan = "<font size='5' color='white'>" + plan + "</font><br>";
            plan = "<form>" + plan.replaceAll("'", "\"") + "</form>";
            Metar = "<h3><font size='6' color='cyan'>METARs</font><br></h3>" + Metar;
            Metar = "<form>" + Metar.replaceAll("'", "\"") + "</form>";
            Taf = "<h3><font size='6' color='cyan'>TAFs</font><br></h3>" + Taf;
            Taf = "<form>" + Taf.replaceAll("'", "\"") + "</form>";
            Pirep = "<h3><font size='6' color='cyan'>PIREPs</font><br></h3>" + Pirep;
            Pirep = "<form>" + Pirep.replaceAll("'", "\"") + "</form>";
			notams = "<h3><font size='6' color='cyan'>NOTAMS</font><br></h3>" + notams;

            String time = NetworkHelper.getVersion("", "weather", null);
            String weather = time + "<br></br>" + plan + Metar + Taf + Pirep + notams;

            // now send to webview
			Message m = mHandler.obtainMessage(MSG_UPDATE_WEATHER, (Object)("'" + Helper.formatJsArgs(weather) + "'"));
			mHandler.sendMessage(m);

        	mHandler.sendEmptyMessage(MSG_NOTBUSY);
        	
        	running = false;
        }        
    }
    
	/**
     * 
     */
    public void cleanup() {
    	if(mWeatherTask != null && mWeatherTask.running) {
	        mWeatherTask.running = false;
	        mWeatherThread.interrupt();
    	}
    }
}