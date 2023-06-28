package com.coughextractor

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
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


class IntroActivity : AppCompatActivity() {
    var textView5: TextView? = null
    var imageView: ImageView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)
        textView5 = findViewById(R.id.textView5)
        textView5?.animate()?.translationY(-1400f)?.setDuration(1000)?.startDelay = 10
        imageView = findViewById(R.id.imageView)
        imageView?.animate()?.translationY(-2000f)?.setDuration(1000)?.startDelay = 10

        val login = getSharedPreferences("Login", MODE_PRIVATE)

        val username = login.getString("Username", null)
        val password = login.getString("Password", null)

        if (username.isNullOrEmpty() || password.isNullOrEmpty()) {
            //new Handler
            Handler().postDelayed({
                val intent = Intent(this@IntroActivity, LoginActivity::class.java)
                startActivity(intent)
                finish()
            }, SPLASH_TIME_OUT.toLong())
        } else {
            try {
                authorization(username, password)
            } catch (e: Exception) {
                println(e.message)
                Handler().postDelayed({
                    val intent = Intent(this@IntroActivity, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                }, SPLASH_TIME_OUT.toLong())
            }
        }
    }

    companion object {
        private const val SPLASH_TIME_OUT = 10
    }

    private fun authorization(username: String, password: String) {
        val jsonObject = JSONObject()
        jsonObject.put("username", username)
        jsonObject.put("password", password)

        val jsonObjectString = jsonObject.toString()

        GlobalScope.launch(Dispatchers.IO) {
            val host = getSharedPreferences("Host", MODE_PRIVATE).getString("Host", null)
            val url = URL("http://${host}/api-token-auth/")
            val httpURLConnection = url.openConnection() as HttpURLConnection
            httpURLConnection.requestMethod = "POST"
            httpURLConnection.setRequestProperty(
                "Content-Type",
                "application/json"
            )
            httpURLConnection.setRequestProperty(
                "Accept",
                "application/json"
            )
            httpURLConnection.doInput = true
            httpURLConnection.doOutput = true

            val out = BufferedWriter(OutputStreamWriter(httpURLConnection.outputStream))
            out.write(jsonObjectString)
            out.close()

            val responseCode = httpURLConnection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = httpURLConnection.inputStream.bufferedReader()
                    .use { it.readText() }  // defaults to UTF-8
                withContext(Dispatchers.Main) {
                    val gson = GsonBuilder().setPrettyPrinting().create()
                    val prettyJson = gson.toJson(JsonParser.parseString(response))
                    val authResponse = gson.fromJson(prettyJson, AuthResponse::class.java)
                    if (!authResponse.token.isNullOrEmpty()) {
                        val sp = getSharedPreferences("Login", MODE_PRIVATE)
                        val Ed = sp.edit()
                        Ed.putString("Username", username)
                        Ed.putString("Password", password)
                        Ed.apply()

                        val intent = Intent(this@IntroActivity, MainActivity::class.java)
                        intent.putExtra("token", authResponse.token)
                        intent.putExtra("userId", authResponse.user_id)
                        startActivity(intent)
                        finish()
                    } else {
                        Handler().postDelayed({
                            val intent = Intent(this@IntroActivity, LoginActivity::class.java)
                            startActivity(intent)
                            finish()
                        }, SPLASH_TIME_OUT.toLong())
                    }
                }
            } else {
                Log.e("HTTPURLCONNECTION_ERROR", responseCode.toString())
            }
        }
    }
}

