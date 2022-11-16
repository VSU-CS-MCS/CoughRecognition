package com.coughextractor

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.TextView
import android.util.Patterns
import android.view.View
import android.widget.Button

class ForgotPasswordActivity : AppCompatActivity() {
    var txtEmail: TextView? = null
    var btnForgotPassword: Button? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)
        txtEmail = findViewById(R.id.editUsername)
        btnForgotPassword = findViewById(R.id.forgotPassword)
        btnForgotPassword?.setOnClickListener(View.OnClickListener {
            val email = txtEmail?.getText().toString().trim { it <= ' ' }
            if (email.isEmpty()) {
                txtEmail?.setError("Email is required")
                txtEmail?.requestFocus()
                return@OnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                txtEmail?.setError("Please enter a valid email")
                txtEmail?.requestFocus()
                return@OnClickListener
            }

            //todo: reset password
        })
    }
}
