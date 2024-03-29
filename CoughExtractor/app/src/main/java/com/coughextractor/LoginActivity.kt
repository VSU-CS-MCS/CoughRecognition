package com.coughextractor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.coughextractor.recorder.AuthResponse
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL


class LoginActivity : AppCompatActivity(), View.OnClickListener {
    private var edtHost: EditText? = null
    private var rememberMeCheckBox: CheckBox? = null
    private var edtEmail: EditText? = null
    private var edtPassword: EditText? = null
    private var btnLogin: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        edtEmail = findViewById(R.id.editUsername)
        edtPassword = findViewById(R.id.editPassword)
        edtHost = findViewById(R.id.editHost)
        val host = getSharedPreferences("Host", MODE_PRIVATE).getString("Host", "")
        edtHost?.setText(host)

        btnLogin = findViewById(R.id.login_btn)
        btnLogin?.setOnClickListener(this)

        rememberMeCheckBox = findViewById(R.id.rememberMe)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.login_btn -> login()
        }
    }

    private fun login() {
        val email = edtEmail!!.text.toString().trim { it <= ' ' }
        val password = edtPassword!!.text.toString().trim { it <= ' ' }
        val host = edtHost!!.text.toString().trim { it <= ' ' }
        if (email.isEmpty()) {
            edtEmail!!.error = "Email is required"
            edtEmail!!.requestFocus()
            return
        }
        if (password.isEmpty()) {
            edtPassword!!.error = "Password is required"
            edtPassword!!.requestFocus()
            return
        }
        if (host.isEmpty()) {
            edtHost!!.error = "Password is required"
            edtHost!!.requestFocus()
            return
        }
        deleteSharedPreferences("Login")
        val sp = getSharedPreferences("Host", MODE_PRIVATE).edit()
        sp.putString("Host", host)
        sp.apply()
        authorization(email, password, host)
    }

    private fun authorization(username: String, password: String, host: String) {
        val jsonObject = JSONObject()
        jsonObject.put("username", username)
        jsonObject.put("password", password)

        // Convert JSONObject to String
        val jsonObjectString = jsonObject.toString()

        GlobalScope.launch(Dispatchers.IO) {
            val url = URL("http://${host}/api-token-auth/")
            val httpURLConnection = url.openConnection() as HttpURLConnection
            httpURLConnection.requestMethod = "POST"
            httpURLConnection.setRequestProperty(
                "Content-Type",
                "application/json"
            ) // The format of the content we're sending to the server
            httpURLConnection.setRequestProperty(
                "Accept",
                "application/json"
            ) // The format of response we want to get from the server
            httpURLConnection.doInput = true
            httpURLConnection.doOutput = true

            val out = BufferedWriter(OutputStreamWriter(httpURLConnection.outputStream))
            out.write(jsonObjectString)
            out.close()

            // Check if the connection is successful
            val responseCode = httpURLConnection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = httpURLConnection.inputStream.bufferedReader()
                    .use { it.readText() }  // defaults to UTF-8
                withContext(Dispatchers.Main) {

                    // Convert raw JSON to pretty JSON using GSON library
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    val prettyJson = gson.toJson(JsonParser.parseString(response))
                    val authResponse = gson.fromJson(prettyJson, AuthResponse::class.java)
                    if (!authResponse.token.isNullOrEmpty()) {
                        if (rememberMeCheckBox?.isChecked!!) {
                            val sp = getSharedPreferences("Login", MODE_PRIVATE).edit()
                            sp.putString("Username", username)
                            sp.putString("Password", password)
                            sp.apply()
                        }

                        val intent = Intent(this@LoginActivity, MainActivity::class.java)
                        intent.putExtra("token", authResponse.token)
                        intent.putExtra("userId", authResponse.user_id)
                        startActivity(intent)
                        finish()
                    }
                }
            } else {
                Log.e("HTTPURLCONNECTION_ERROR", responseCode.toString())
            }
        }
    }
}
