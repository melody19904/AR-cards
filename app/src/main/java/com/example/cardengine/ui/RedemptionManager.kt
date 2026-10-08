package com.example.cardengine.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout

object RedemptionManager {
    fun showRedemptionDialog(
        context: Context,
        cardId: String,
        cardName: String,
        onVerifyClick: (String, AlertDialog) -> Unit
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Stall PIN"
            gravity = Gravity.CENTER
            textSize = 24f
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 16)
            addView(input)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Issue Physical Card")
            .setMessage("Hand phone to stall operator to verify claim for: $cardName")
            .setView(container)
            // Passing null prevents the button from auto-closing the dialog before the network finishes
            .setPositiveButton("Verify & Burn", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val enteredPin = input.text.toString().trim()
                if (enteredPin.isNotEmpty()) {
                    onVerifyClick(enteredPin, dialog)
                }
            }
        }
        dialog.show()
    }
}