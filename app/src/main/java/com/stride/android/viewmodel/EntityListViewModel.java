package com.stride.android.viewmodel;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.stride.android.model.EntityListState;
import com.stride.android.model.EntityRow;
import com.stride.android.utils.FeatureUi;
import com.stride.android.utils.SupabaseApi;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns workspace queries and converts backend records into immutable row data. */
public final class EntityListViewModel extends ViewModel {
    private final MutableLiveData<EntityListState> state = new MutableLiveData<>(
            new EntityListState(Collections.emptyList(), false, null));
    private SupabaseApi api;
    private String table;
    private String userId;
    private int generation;

    public LiveData<EntityListState> state() {
        return state;
    }

    public void configure(SupabaseApi api, String table, String userId) {
        this.api = api;
        this.table = table;
        this.userId = userId;
    }

    public void load(String extraQuery) {
        int request = ++generation;
        state.setValue(new EntityListState(Collections.emptyList(), true, null));
        String order = "notes".equals(table) ? "updated_at.desc"
                : "calendar_events".equals(table) ? "start_time.asc" : "created_at.desc";
        String query = SupabaseApi.and("select=*", SupabaseApi.eq("user_id", userId),
                "order=" + order);
        if (!extraQuery.isEmpty()) {
            query = SupabaseApi.and(query, extraQuery);
        }
        api.get(table, query, (records, error) -> {
            if (request != generation) {
                return;
            }
            List<EntityRow> rows = new ArrayList<>();
            if (error == null) {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record != null) {
                        rows.add(toRow(record));
                    }
                }
            }
            state.setValue(new EntityListState(rows, false, error));
        });
    }

    public void insert(JSONObject values, SupabaseApi.Callback callback) {
        api.insert(table, values, callback);
    }

    public void cancelLoad() {
        generation++;
    }

    private EntityRow toRow(JSONObject record) {
        String title = record.optString("title", "notes".equals(table) ? "Untitled Note" : "");
        String subtitle;
        switch (table) {
            case "notes":
                subtitle = FeatureUi.strip(record.optString("content"));
                break;
            case "goals":
                subtitle = record.optString("current_value", "0") + " / "
                        + record.optString("target_value", "100") + " "
                        + record.optString("unit", "%");
                break;
            case "calendar_events":
                subtitle = record.optString("category", "Focus") + " \u00b7 "
                        + record.optString("start_time", "Any time");
                break;
            default:
                subtitle = record.optString("status", "todo");
        }
        return new EntityRow(record.optString("id"), title, subtitle);
    }
}
