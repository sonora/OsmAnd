package net.osmand.plus.myplaces.tracks.dialogs.viewholders;

import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.track.data.TracksGroup;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.plus.utils.ColorUtilities;
import net.osmand.plus.utils.UiUtilities;

public class TracksGroupViewHolder extends RecyclerView.ViewHolder {

	protected final OsmandApplication app;
	protected final UiUtilities uiUtilities;
	protected final TrackGroupsListener listener;

	protected final TextView title;
	protected final TextView description;
	protected final ImageView icon;
	protected final CompoundButton checkbox;
	protected final View checkboxContainer;
	protected final View menuButton;
	protected final View divider;

	protected final boolean nightMode;

	public TracksGroupViewHolder(@NonNull View view, @Nullable TrackGroupsListener listener,
	                             boolean nightMode, boolean selectionMode) {
		super(view);
		this.listener = listener;
		this.nightMode = nightMode;
		app = (OsmandApplication) view.getContext().getApplicationContext().getApplicationContext();
		uiUtilities = app.getUIUtilities();

		title = view.findViewById(R.id.title);
		description = view.findViewById(R.id.description);
		icon = view.findViewById(R.id.icon);
		checkbox = view.findViewById(R.id.checkbox);
		checkboxContainer = view.findViewById(R.id.checkbox_container);
		menuButton = view.findViewById(R.id.menu_button);
		divider = view.findViewById(R.id.divider);

		setupSelectionMode(selectionMode);
	}

	private void setupSelectionMode(boolean selectionMode) {
		int margin = app.getResources().getDimensionPixelSize(R.dimen.content_padding);
		ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) icon.getLayoutParams();
		AndroidUtils.setMargins(params, margin, 0, selectionMode ? 0 : margin, 0);

		AndroidUiHelper.updateVisibility(menuButton, false);
		AndroidUiHelper.updateVisibility(checkboxContainer, selectionMode);
		AndroidUiHelper.updateVisibility(itemView.findViewById(R.id.direction_icon), false);
		UiUtilities.setupCompoundButton(nightMode, ColorUtilities.getActiveColor(app, nightMode), checkbox);
	}

	public void bindView(@NonNull TracksGroup tracksGroup, boolean showDivider) {
		boolean selected = listener != null && listener.isTracksGroupSelected(tracksGroup);
		checkbox.setChecked(selected);

		checkboxContainer.setOnClickListener(v -> {
			if (listener != null) {
				listener.onTracksGroupSelected(tracksGroup, !selected);
			}
		});
		itemView.setOnClickListener(v -> {
			if (listener != null) {
				listener.onTracksGroupClicked(tracksGroup);
			}
		});
		AndroidUiHelper.updateVisibility(divider, showDivider);
	}

	public interface TrackGroupsListener {

		default boolean isTracksGroupSelected(@NonNull TracksGroup group) {
			return false;
		}

		default void onTracksGroupSelected(@NonNull TracksGroup group, boolean selected) {

		}

		default void onTracksGroupClicked(@NonNull TracksGroup group) {

		}
	}
}