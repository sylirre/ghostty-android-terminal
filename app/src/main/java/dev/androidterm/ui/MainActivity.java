package dev.androidterm.ui;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Placeholder; replaced by the terminal UI in a later task. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("AndroidTerm scaffold");
        setContentView(tv);
    }
}
