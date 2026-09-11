package com.n30dyn4m1c.photosphere.rooms;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.n30dyn4m1c.photosphere.R;
import com.n30dyn4m1c.photosphere.storage.RoomLibrary;
import com.n30dyn4m1c.photosphere.storage.SphereImageStore;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saved interiors: tap a room to open it in Preview.
 */
public class RoomsFragment extends Fragment {

    public interface Host {
        void onRoomSelected(SphereImageStore.StitchedSphere room);
        void onCaptureRequested();
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();

    private GridView grid;
    private View empty;
    private View header;
    private RoomAdapter adapter;
    private final List<File> rooms = new ArrayList<File>();

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.fragment_rooms, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        header = view.findViewById(R.id.rooms_header);
        grid = view.findViewById(R.id.rooms_grid);
        empty = view.findViewById(R.id.rooms_empty);
        adapter = new RoomAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View item, int position, long id) {
                Host host = host();
                if (host != null && position >= 0 && position < rooms.size()) {
                    host.onRoomSelected(RoomLibrary.open(rooms.get(position)));
                }
            }
        });
        Button action = view.findViewById(R.id.rooms_empty_action);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Host host = host();
                if (host != null) {
                    host.onCaptureRequested();
                }
            }
        });
        ViewCompat.setOnApplyWindowInsetsListener(view, new androidx.core.view.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                header.setPadding(
                        header.getPaddingLeft(),
                        insets.getSystemWindowInsetTop() + dp(12),
                        header.getPaddingRight(),
                        header.getPaddingBottom()
                );
                return insets;
            }
        });
        reload();
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onDestroyView() {
        ioExecutor.shutdownNow();
        thumbs.clear();
        super.onDestroyView();
    }

    private void reload() {
        if (!isAdded()) {
            return;
        }
        rooms.clear();
        rooms.addAll(RoomLibrary.listFiles(requireContext()));
        boolean hasRooms = !rooms.isEmpty();
        grid.setVisibility(hasRooms ? View.VISIBLE : View.GONE);
        empty.setVisibility(hasRooms ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private Host host() {
        if (getActivity() instanceof Host) {
            return (Host) getActivity();
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class RoomAdapter extends BaseAdapter {
        private final DateFormat dates = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT);

        @Override
        public int getCount() {
            return rooms.size();
        }

        @Override
        public Object getItem(int position) {
            return rooms.get(position);
        }

        @Override
        public long getItemId(int position) {
            return rooms.get(position).getAbsolutePath().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_room, parent, false);
            }
            File file = rooms.get(position);
            TextView title = view.findViewById(R.id.room_title);
            TextView date = view.findViewById(R.id.room_date);
            ImageView thumb = view.findViewById(R.id.room_thumb);
            title.setText(getString(R.string.rooms_item_untitled) + " " + (rooms.size() - position));
            date.setText(dates.format(new Date(file.lastModified())));
            Bitmap cached = thumbs.get(file.getAbsolutePath());
            if (cached != null) {
                thumb.setImageBitmap(cached);
            } else {
                thumb.setImageBitmap(null);
                loadThumb(file, thumb);
            }
            return view;
        }
    }

    private void loadThumb(final File file, final ImageView target) {
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodeThumb(file);
                if (bitmap == null) {
                    return;
                }
                thumbs.put(file.getAbsolutePath(), bitmap);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isAdded() && target.isAttachedToWindow()) {
                            target.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap decodeThumb(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, bounds.outWidth / 400);
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }
}
