package com.gathin.tvhome;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The launcher's menus and questions, in the look of Home: the glass panel, rows that turn white with focus, and pill
 * buttons. Its Builder is used as AlertDialog's is, and Android's own dialog is never shown, so every box looks the same.
 */
final class TvDialog extends Dialog {
    private static final int WIDTH_DP = 500;

    private final TextView[] buttons = new TextView[3];
    private View firstFocus;

    private TvDialog(Context context) {
        super(context);
    }

    /** One of DialogInterface.BUTTON_POSITIVE, _NEGATIVE or _NEUTRAL; null if this dialog doesn't have it. */
    TextView getButton(int which) {
        return which == BUTTON_POSITIVE ? buttons[0] : which == BUTTON_NEGATIVE ? buttons[1] : which == BUTTON_NEUTRAL ? buttons[2] : null;
    }

    @Override
    public void show() {
        super.show();
        if (firstFocus != null) firstFocus.requestFocus();
    }

    static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private CharSequence[] items;
        private int checked = -1;
        private boolean singleChoice;
        private DialogInterface.OnClickListener itemListener;
        private View view;
        private EditText input;
        private final CharSequence[] buttonTexts = new CharSequence[3];
        private final DialogInterface.OnClickListener[] buttonListeners = new DialogInterface.OnClickListener[3];
        private DialogInterface.OnCancelListener cancelListener;
        private DialogInterface.OnDismissListener dismissListener;
        private DialogInterface.OnKeyListener keyListener;

        Builder(Context context) {
            this.context = context;
        }

        Builder setTitle(int text) {
            title = context.getText(text);
            return this;
        }

        Builder setTitle(CharSequence text) {
            title = text;
            return this;
        }

        Builder setMessage(int text) {
            message = context.getText(text);
            return this;
        }

        Builder setMessage(CharSequence text) {
            message = text;
            return this;
        }

        /** A list that closes when a row is chosen. */
        Builder setItems(CharSequence[] rows, DialogInterface.OnClickListener listener) {
            items = rows;
            itemListener = listener;
            singleChoice = false;
            return this;
        }

        /** Marks one row of setItems as the current one, which is where focus starts. */
        Builder setChecked(int row) {
            checked = row;
            return this;
        }

        /** A list with the current choice marked. As with AlertDialog, the listener closes it. */
        Builder setSingleChoiceItems(CharSequence[] rows, int current, DialogInterface.OnClickListener listener) {
            items = rows;
            checked = current;
            itemListener = listener;
            singleChoice = true;
            return this;
        }

        Builder setView(View content) {
            view = content;
            return this;
        }

        /** A field to type in. It gets focus, and the dialog styles it. */
        Builder setInput(EditText field) {
            input = field;
            return this;
        }

        Builder setPositiveButton(int text, DialogInterface.OnClickListener listener) {
            return button(0, text, listener);
        }

        Builder setNegativeButton(int text, DialogInterface.OnClickListener listener) {
            return button(1, text, listener);
        }

        Builder setNeutralButton(int text, DialogInterface.OnClickListener listener) {
            return button(2, text, listener);
        }

        private Builder button(int slot, int text, DialogInterface.OnClickListener listener) {
            buttonTexts[slot] = context.getText(text);
            buttonListeners[slot] = listener;
            return this;
        }

        Builder setOnCancelListener(DialogInterface.OnCancelListener listener) {
            cancelListener = listener;
            return this;
        }

        Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            dismissListener = listener;
            return this;
        }

        Builder setOnKeyListener(DialogInterface.OnKeyListener listener) {
            keyListener = listener;
            return this;
        }

        TvDialog show() {
            TvDialog dialog = create();
            dialog.show();
            return dialog;
        }

        TvDialog create() {
            TvDialog dialog = new TvDialog(context);
            LayoutInflater inflater = LayoutInflater.from(context);
            View root = inflater.inflate(R.layout.dialog_tv, null);
            if (context.getResources().getBoolean(R.bool.rtl_menu_bar)) root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

            TextView heading = root.findViewById(R.id.dialogTitle);
            if (TextUtils.isEmpty(title)) heading.setVisibility(View.GONE);
            else heading.setText(title);

            TextView body = root.findViewById(R.id.dialogMessage);
            if (TextUtils.isEmpty(message)) body.setVisibility(View.GONE);
            else body.setText(message);

            LinearLayout rows = root.findViewById(R.id.dialogRows);
            FrameLayout extra = root.findViewById(R.id.dialogView);
            View scroller = root.findViewById(R.id.dialogScroll);
            View firstRow = null;
            View checkedRow = null;
            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    View row = row(inflater, rows, dialog, i);
                    rows.addView(row);
                    if (i == 0) firstRow = row;
                    if (i == checked) checkedRow = row;
                }
            }
            if (input != null) {
                input.setBackgroundResource(R.drawable.input_field_bg);
                int side = dp(14);
                input.setPadding(side, 0, side, 0);
                input.setMinHeight(dp(46));
                input.setTextSize(16);
                input.setTextColor(0xFFFFFFFF);
                input.setHintTextColor(0x80FFFFFF);
                input.setTypeface(UiFont.get(context, R.string.font_regular));
                extra.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (view != null) {
                // A view that scrolls for itself takes the place of the page's own scrolling.
                ((ViewGroup) scroller.getParent()).removeView(scroller);
                FrameLayout content = root.findViewById(R.id.dialogContent);
                content.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (items == null && input == null && TextUtils.isEmpty(message) && view == null) scroller.setVisibility(View.GONE);

            View positive = addButton(root, dialog, 0, DialogInterface.BUTTON_POSITIVE, R.id.dialogPositive);
            View negative = addButton(root, dialog, 1, DialogInterface.BUTTON_NEGATIVE, R.id.dialogNegative);
            View neutral = addButton(root, dialog, 2, DialogInterface.BUTTON_NEUTRAL, R.id.dialogNeutral);
            // Down from a field goes to OK, not whichever button sits nearest (Rename's "Use original name").
            if (input != null && positive != null) input.setNextFocusDownId(R.id.dialogPositive);
            if (positive == null && negative == null && neutral == null) root.findViewById(R.id.dialogButtons).setVisibility(View.GONE);

            // The current choice, then the first row or the field; a question opens on its safe answer.
            dialog.firstFocus = checkedRow != null ? checkedRow : firstRow != null ? firstRow : input != null ? input
                    : negative != null ? negative : positive != null ? positive : neutral;

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(dp(WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setDimAmount(0.6f);
            }
            if (cancelListener != null) dialog.setOnCancelListener(cancelListener);
            if (dismissListener != null) dialog.setOnDismissListener(dismissListener);
            if (keyListener != null) dialog.setOnKeyListener(keyListener);
            return dialog;
        }

        private View row(LayoutInflater inflater, ViewGroup parent, TvDialog dialog, int position) {
            View row = inflater.inflate(R.layout.item_input, parent, false);
            row.findViewById(R.id.dot).setVisibility(View.GONE);
            row.findViewById(R.id.status).setVisibility(View.GONE);
            TextView label = row.findViewById(R.id.title);
            label.setText(items[position]);
            label.setEllipsize(TextUtils.TruncateAt.END);
            ImageView mark = row.findViewById(R.id.icon);
            if (singleChoice || checked >= 0) {
                mark.setImageTintList(null);
                mark.setImageResource(R.drawable.library_check);
                // Every row keeps the mark's space, so the names stay in line.
                mark.setVisibility(position == checked ? View.VISIBLE : View.INVISIBLE);
            } else {
                mark.setVisibility(View.GONE);
                ((ViewGroup.MarginLayoutParams) label.getLayoutParams()).setMarginStart(0);
            }
            row.setOnClickListener(v -> {
                if (itemListener != null) itemListener.onClick(dialog, position);
                if (!singleChoice) dialog.dismiss();
            });
            return row;
        }

        private View addButton(View root, TvDialog dialog, int slot, int which, int id) {
            TextView button = root.findViewById(id);
            if (buttonTexts[slot] == null) {
                button.setVisibility(View.GONE);
                return null;
            }
            button.setText(buttonTexts[slot]);
            button.setOnClickListener(v -> {
                if (buttonListeners[slot] != null) buttonListeners[slot].onClick(dialog, which);
                dialog.dismiss();
            });
            dialog.buttons[slot] = button;
            return button;
        }

        private int dp(int value) {
            return (int) (value * context.getResources().getDisplayMetrics().density);
        }
    }
}
