package com.ytmlegacy.android.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/** One page of the app (home, an album, search…). Lives in a tab's back stack. */
public abstract class Screen {
    protected MainActivity act;
    private View view;
    private boolean destroyed;

    final void attach(MainActivity activity) {
        act = activity;
    }

    final View view(LayoutInflater inflater, ViewGroup parent) {
        if (view == null) view = onCreateView(inflater, parent);
        return view;
    }

    protected View getView() {
        return view;
    }

    public abstract String title();

    protected abstract View onCreateView(LayoutInflater inflater, ViewGroup parent);

    public void onShown() { }

    public void onHidden() { }

    public void onDestroy() {
        destroyed = true;
    }

    public boolean isDestroyed() {
        return destroyed || act == null || act.isFinishing();
    }

    /** True when the screen handled the back key itself. */
    public boolean onBack() {
        return false;
    }

    /** The tab of this screen was tapped again. */
    public void onReselected() { }

    public void onPlayerChanged() { }

    public void onLibraryChanged() { }

    /** Search screens show the search field in the action bar. */
    public boolean isSearch() {
        return false;
    }
}
