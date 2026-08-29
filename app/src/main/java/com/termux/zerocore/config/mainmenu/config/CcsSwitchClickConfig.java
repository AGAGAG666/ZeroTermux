package com.termux.zerocore.config.mainmenu.config;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;

import com.termux.R;
import com.termux.zerocore.ccs.CcsSwitchActivity;

/** 打开内置 CC Switch。 */
public class CcsSwitchClickConfig extends BaseMenuClickConfig {
    @Override
    public Drawable getIcon(Context context) {
        return context.getDrawable(R.mipmap.code_view);
    }

    @Override
    public String getString(Context context) {
        return context.getString(R.string.cc_switch_title);
    }

    @Override
    public void onClick(View view, Context context) {
        context.startActivity(new Intent(context, CcsSwitchActivity.class));
    }
}
