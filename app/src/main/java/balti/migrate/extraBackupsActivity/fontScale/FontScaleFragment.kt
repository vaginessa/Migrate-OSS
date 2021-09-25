package balti.migrate.extraBackupsActivity.fontScale

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import balti.migrate.AppInstance.Companion.fontScale
import balti.migrate.R
import balti.migrate.extraBackupsActivity.ParentFragmentForExtras
import balti.migrate.extraBackupsActivity.ParentReaderForExtras
import balti.module.baltitoolbox.functions.Misc.runOnMainThread
import balti.module.baltitoolbox.functions.Misc.runSuspendFunction
import balti.module.baltitoolbox.functions.Misc.showErrorDialog
import balti.module.baltitoolbox.functions.Misc.tryIt

class FontScaleFragment: ParentFragmentForExtras(R.layout.extra_fragment_font_scale) {

    override lateinit var readTask: ParentReaderForExtras

    private fun startReadTask(){
        showStockWarning({
            delegateSalView?.visibility = View.GONE
            // call ReadFontScaleKotlin
            readTask = ReadFontScaleKotlin(this)

            runSuspendFunction {
                val jobResults = readTask.executeWithResult()

                if (jobResults.success) {

                    val receivedFontScale = jobResults.result.toString().toDouble()

                    runOnMainThread {

                        if (receivedFontScale > 0) fontScale = receivedFontScale
                        else {
                            setDefaultValueForExtraNotPresent({
                                fontScale = 1.0
                            }, R.string.fontScale_label, R.string.no_default_font_scale)
                        }

                        delegateStatusText?.text = fontScale.toString()

                        tryIt({
                            delegateMainItem?.setOnClickListener {
                                mActivity?.let { it1 ->
                                    AlertDialog.Builder(it1)
                                        .setTitle(R.string.fontScale_label)
                                        .setMessage(fontScale.toString())
                                        .setNegativeButton(R.string.close, null)
                                        .show()
                                }

                            }
                        }, true)
                    }
                } else {
                    delegateCheckbox?.isChecked = false
                    showErrorDialog(
                        jobResults.result.toString(),
                        getString(R.string.error_reading_fontScale)
                    )
                }
            }

        }, {
            delegateCheckbox?.isChecked = false
        })
    }

    override fun onCreateView(savedInstanceState: Bundle?) {

        delegateCheckbox?.setOnCheckedChangeListener { _, isChecked ->

            mActivity?.run {

                if (isChecked){
                    startReadTask()
                }
                else {
                    fontScale = null
                    deselectExtra(null, listOf(delegateProgressBar, delegateStatusText), listOf(delegateSalView))
                }
            }
        }
    }

    override val viewIdSalView: Int = R.id.sal_dpi

    override val viewIdStatusText: Int = R.id.dpi_read_text_status
    override val viewIdProgressBar: Int = R.id.dpi_read_progress
    override val viewIdCheckbox: Int = R.id.dpi_fragment_checkbox

}