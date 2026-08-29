package com.termux.zerocore.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import com.example.xh_lib.utils.UUtils
import com.termux.R
import com.termux.zerocore.dialog.KeyWordFunDialog
import com.termux.zerocore.ftp.utils.UserSetManage
import com.termux.zerocore.guide.TermuxGuideActivity
import com.termux.zerocore.guide.TermuxGuideActivity.Companion.GUIDE_CREATE_FOLDER
import com.termux.zerocore.guide.TermuxGuideActivity.Companion.GUIDE_EXTRA
import com.termux.zerocore.guide.TermuxGuideActivity.Companion.GUIDE_EXTRA_JUMP_OTHER

class ZeroTermuxSettingsActivity : BaseTitleActivity() {

    private val inputMethodTriggerCloseSwitch by lazy { findViewById<SwitchCompat>(R.id.input_method_trigger_close_switch) }
    private val inputMethodTriggerCloseLl by lazy { findViewById<LinearLayout>(R.id.input_method_trigger_close_ll) }

    private val styleTriggerOffSwitch by lazy { findViewById<SwitchCompat>(R.id.style_trigger_off_switch) }
    private val styleTriggerOffLl by lazy { findViewById<LinearLayout>(R.id.style_trigger_off_ll) }

    private val isToolShowSwitch by lazy { findViewById<SwitchCompat>(R.id.is_tool_show_switch) }
    private val isToolShowLl by lazy { findViewById<LinearLayout>(R.id.is_tool_show_ll) }

    private val volumeFunctionSwitch by lazy { findViewById<SwitchCompat>(R.id.volume_function_switch) }
    private val volumeFunctionLl by lazy { findViewById<LinearLayout>(R.id.volume_function_ll) }

    private val editorWordWrapSwitch by lazy { findViewById<SwitchCompat>(R.id.editor_word_wrap_switch) }
    private val editorWordWrapLl by lazy { findViewById<LinearLayout>(R.id.editor_word_wrap_ll) }

    private val mSettingsKeywordFunCardViewLayout by lazy { findViewById<CardView>(R.id.settings_keyword_fun_card) }
    private val mSettingsKeywordFunTextView by lazy { findViewById<TextView>(R.id.settings_keyword_fun_text_summary) }
    private val ccsWebPortCard by lazy { findViewById<CardView>(R.id.ccs_web_port_card) }
    private val ccsWebPortSummary by lazy { findViewById<TextView>(R.id.ccs_web_port_summary) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_zero_termux_settings)
        setBaseTitle(UUtils.getString(R.string.zt_settings))
        initView()
        initStatus()
    }

    private fun initView() {
        setSwitchStatus(inputMethodTriggerCloseSwitch, inputMethodTriggerCloseLl)
        setSwitchStatus(styleTriggerOffSwitch, styleTriggerOffLl)
        setSwitchStatus(isToolShowSwitch, isToolShowLl)
        setSwitchStatus(volumeFunctionSwitch, volumeFunctionLl)
        setSwitchStatus(editorWordWrapSwitch, editorWordWrapLl)
        ccsWebPortCard.setOnClickListener { showCcsWebPortDialog() }
        findViewById<CardView>(R.id.save_path).setOnClickListener {
            val intent = Intent(this, TermuxGuideActivity::class.java)
            intent.putExtra(GUIDE_EXTRA, GUIDE_CREATE_FOLDER)
            intent.putExtra(GUIDE_EXTRA_JUMP_OTHER, true)
            startActivity(intent)
        }
    }

    private fun initStatus() {
        val ztUserBean = UserSetManage.get().getZTUserBean()
        inputMethodTriggerCloseSwitch.isChecked = ztUserBean.isInputMethodTriggerClose
        styleTriggerOffSwitch.isChecked = ztUserBean.isStyleTriggerOff
        isToolShowSwitch.isChecked = ztUserBean.isToolShow
        volumeFunctionSwitch.isChecked = ztUserBean.isResetVolume
        editorWordWrapSwitch.isChecked = ztUserBean.isEditorWordWrap
        ccsWebPortSummary.text = getString(R.string.ccs_web_port_summary, ztUserBean.ccsWebPort)
        mSettingsKeywordFunTextView.text =
            "${UUtils.getString(R.string.settings_keyword_summary1)}: " +
                "${KeyWordFunDialog.getDoubleClickString(ztUserBean.doubleClickFun)}\n" +
                "${UUtils.getString(R.string.settings_keyword_summary)}"
        mSettingsKeywordFunCardViewLayout.setOnClickListener {
            val keyWordFunDialog = KeyWordFunDialog(this)
            keyWordFunDialog.show()
            keyWordFunDialog.setOnDismissListener {
                val ztUserBean1 = UserSetManage.get().getZTUserBean()
                mSettingsKeywordFunTextView.text =
                    "${UUtils.getString(R.string.settings_keyword_summary1)}: " +
                        "${KeyWordFunDialog.getDoubleClickString(ztUserBean1.doubleClickFun)}\n" +
                        "${UUtils.getString(R.string.settings_keyword_summary)}"
            }
        }
    }

    private fun showCcsWebPortDialog() {
        val currentPort = UserSetManage.get().getZTUserBean().ccsWebPort
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentPort.toString())
            setSelection(text.length)
            hint = "17132"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ccs_web_port_dialog_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val port = input.text.toString().trim().toIntOrNull()
                if (port == null || port !in 1024..65535) {
                    Toast.makeText(this, R.string.ccs_web_port_invalid, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val bean = UserSetManage.get().getZTUserBean()
                bean.ccsWebPort = port
                UserSetManage.get().setZTUserBean(bean)
                ccsWebPortSummary.text = getString(R.string.ccs_web_port_summary, port)
                Toast.makeText(this, R.string.ccs_web_port_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun setSwitchStatus(switchCompat: SwitchCompat, linearLayout: LinearLayout) {
        linearLayout.setOnClickListener {
            switchCompat.isChecked = !(switchCompat.isChecked)
        }
        switchCompat.setOnCheckedChangeListener { _, _ ->
            val ztUserBean = UserSetManage.get().getZTUserBean()
            when (switchCompat) {
                inputMethodTriggerCloseSwitch -> {
                    ztUserBean.isInputMethodTriggerClose = switchCompat.isChecked
                }
                styleTriggerOffSwitch -> {
                    ztUserBean.isStyleTriggerOff = switchCompat.isChecked
                }
                isToolShowSwitch -> {
                    ztUserBean.isToolShow = switchCompat.isChecked
                }
                volumeFunctionSwitch -> {
                    ztUserBean.isResetVolume = switchCompat.isChecked
                }
                editorWordWrapSwitch -> {
                    ztUserBean.isEditorWordWrap = switchCompat.isChecked
                }
            }
            UserSetManage.get().setZTUserBean(ztUserBean)
        }
    }
}
