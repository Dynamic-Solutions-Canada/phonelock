package home.phonelock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * The child's family chat, opened from its icon on the home screen or a message's notification. It is
 * scheduled and allowed like an app (see {@link BuiltInApps}) and closes when it isn't allowed right now. Shows
 * the thread with who sent each message and when, and a text box with Send; when the parents' slow mode or
 * timeout applies, Send is off and a line says until when. The thread and sending are {@link ChatLog}'s.
 */
public class ChatActivity extends Activity {
    /** Re-checks the schedule while open, so a grant running out closes the screen. */
    private static final long RECHECK_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final MessageAdapter adapter = new MessageAdapter();
    private ListView list;
    private TextView empty;
    private TextView waitNote;
    private EditText input;
    private TextView send;

    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            if (allowedNow()) handler.postDelayed(this, RECHECK_MS);
        }
    };

    /** Turns Send back on when a slow mode or timeout ends. */
    private final Runnable waitOver = this::showSendState;

    private final BroadcastReceiver changed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Policy.ACTION_CHANGED.equals(i.getAction()) && !allowedNow()) return;
            show();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        // fitsSystemWindows replaces a view's own padding with the system bars' insets (and the keyboard's, with
        // adjustResize), so the frame keeps clear of them and the layout inside it carries the margins.
        LinearLayout frame = new LinearLayout(this);
        frame.setFitsSystemWindows(true);
        frame.setBackground(ScreenStyle.background());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(16));
        frame.addView(root, new LinearLayout.LayoutParams(-1, -1));

        root.addView(header());

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setStackFromBottom(true);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1);
        ll.topMargin = dp(16);
        root.addView(list, ll);

        empty = ScreenStyle.text(this, getString(R.string.chat_empty), 15, ScreenStyle.MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));

        waitNote = ScreenStyle.text(this, "", 14, ScreenStyle.MUTED);
        waitNote.setPadding(dp(8), dp(8), dp(8), 0);
        root.addView(waitNote);

        root.addView(composer());
        setContentView(frame);
    }

    /** The title, with Done on the right. */
    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(
                ScreenStyle.text(this, BuiltInApps.label(this, BuiltInApps.CHAT), 28, ScreenStyle.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView done = ScreenStyle.pill(this, getString(R.string.done), ScreenStyle.BUTTON);
        done.setPadding(dp(20), 0, dp(20), 0);
        done.setOnClickListener(v -> finish());
        h.addView(done, new LinearLayout.LayoutParams(-2, dp(44)));
        return h;
    }

    /** The text box and Send. */
    private View composer() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.BOTTOM);
        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(ChatHistory.MAX_TEXT)});
        input.setHint(R.string.chat_hint);
        input.setTextColor(ScreenStyle.TEXT);
        input.setHintTextColor(ScreenStyle.MUTED);
        input.setBackground(ScreenStyle.rounded(this, ScreenStyle.CARD, 22));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        send = ScreenStyle.pill(this, getString(R.string.chat_send), ScreenStyle.ACCENT);
        send.setTextColor(ScreenStyle.ON_ACCENT);
        send.setPadding(dp(20), 0, dp(20), 0);
        send.setOnClickListener(v -> onSend());
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, dp(48));
        sl.leftMargin = dp(10);
        row.addView(send, sl);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = dp(10);
        row.setLayoutParams(rl);
        return row;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!allowedNow()) return;
        ChatLog.visible = true;
        handler.postDelayed(recheck, RECHECK_MS);
        IntentFilter f = new IntentFilter(ChatLog.ACTION_CHANGED);
        f.addAction(Policy.ACTION_CHANGED);
        // Both come from this app only.
        registerReceiver(changed, f, Context.RECEIVER_NOT_EXPORTED);
        show();
    }

    @Override
    protected void onPause() {
        ChatLog.visible = false;
        handler.removeCallbacks(recheck);
        handler.removeCallbacks(waitOver);
        try {
            unregisterReceiver(changed);
        } catch (IllegalArgumentException ignored) {
        }
        super.onPause();
    }

    /**
     * Like any app, this screen is open only while it's allowed (schedule, a grant, or parent mode). Closes it
     * and returns false otherwise; checked on opening, on policy changes and periodically. Sending checks again.
     */
    private boolean allowedNow() {
        if (BuiltInApps.allowedNow(this, BuiltInApps.CHAT)) return true;
        if (!isFinishing()) {
            toast(getString(R.string.tools_not_allowed_now, BuiltInApps.label(this, BuiltInApps.CHAT)));
            finish();
        }
        return false;
    }

    /** Shows the thread as stored now, marks it seen, and updates Send. */
    private void show() {
        List<ChatHistory.Msg> msgs = ChatLog.messages(this);
        adapter.set(msgs);
        boolean none = msgs.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (!none) ChatLog.seen(this, msgs.get(msgs.size() - 1).seq);
        else ChatLog.cancelNotices(this);
        showSendState();
    }

    /** Send on or off, with the line saying why it's off and until when; re-checked when the wait ends. */
    private void showSendState() {
        handler.removeCallbacks(waitOver);
        ChatHistory.Wait wait = ChatLog.sendWait(this);
        boolean can = wait == null;
        send.setEnabled(can);
        send.setAlpha(can ? 1f : 0.4f);
        if (can) {
            waitNote.setVisibility(View.GONE);
            return;
        }
        String end = ChatLog.when(this, wait.until);
        waitNote.setText(getString(wait.timeout ? R.string.chat_wait_timeout : R.string.chat_wait_slow, end));
        waitNote.setVisibility(View.VISIBLE);
        handler.postDelayed(waitOver, Math.max(1000, wait.until - System.currentTimeMillis() + 500));
    }

    /** Send: hands the text to {@link ChatLog#send}, which decides whether it may go now. */
    private void onSend() {
        switch (ChatLog.send(this, input.getText().toString())) {
            case ChatLog.SENT:
                input.setText("");
                break;
            case ChatLog.NOT_ALLOWED:
                allowedNow();
                break;
            case ChatLog.NOT_SET_UP:
                toast(getString(R.string.home_not_set_up));
                break;
            default: // EMPTY, or WAIT: the note already says until when
                showSendState();
                break;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return ScreenStyle.dp(this, v);
    }

    /** The thread's rows, built once per visible row and reused as the list scrolls. */
    private final class MessageAdapter extends BaseAdapter {
        private List<ChatHistory.Msg> msgs = new ArrayList<>();

        void set(List<ChatHistory.Msg> msgs) {
            this.msgs = msgs;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return msgs.size();
        }

        @Override
        public Object getItem(int i) {
            return msgs.get(i);
        }

        @Override
        public long getItemId(int i) {
            return msgs.get(i).seq;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Row row = convert == null ? new Row() : (Row) convert.getTag();
            row.bind(msgs.get(i));
            return row.view;
        }
    }

    /**
     * One message: a rounded bubble with the sender, the text and the time, on the right for the child's own
     * messages and on the left for the parents'.
     */
    private final class Row {
        final LinearLayout view = new LinearLayout(ChatActivity.this);
        final LinearLayout bubble = new LinearLayout(ChatActivity.this);
        final TextView sender = ScreenStyle.text(ChatActivity.this, "", 13, ScreenStyle.ACCENT);
        final TextView text = ScreenStyle.text(ChatActivity.this, "", 16, ScreenStyle.TEXT);
        final TextView time = ScreenStyle.text(ChatActivity.this, "", 12, ScreenStyle.MUTED);

        Row() {
            view.setPadding(0, dp(4), 0, dp(4));
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
            text.setTextIsSelectable(false);
            bubble.addView(sender);
            bubble.addView(text);
            bubble.addView(time);
            view.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
            view.setTag(this);
        }

        void bind(ChatHistory.Msg m) {
            boolean mine = m.mine();
            view.setGravity(mine ? Gravity.END : Gravity.START);
            // Leave room on the other side, so it's clear at a glance who wrote what.
            view.setPadding(mine ? dp(48) : 0, dp(4), mine ? 0 : dp(48), dp(4));
            bubble.setBackground(
                    ScreenStyle.rounded(ChatActivity.this, mine ? ScreenStyle.BUTTON : ScreenStyle.CARD, 18));
            sender.setText(ChatLog.sender(ChatActivity.this, m.from));
            sender.setTextColor(mine ? ScreenStyle.MUTED : ScreenStyle.ACCENT);
            text.setText(m.text);
            time.setText(m.sent ? ChatLog.stamp(ChatActivity.this, m.at) : getString(R.string.chat_not_sent));
        }
    }
}
