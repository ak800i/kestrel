package dev.kestrel.testapp

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout

/** A web page in a WebView, without JavaScript and without network access. */
class WebActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Web fixture")
        val web = WebView(this)
        page.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
    }

    private companion object {
        const val PAGE = """<!doctype html>
<html>
<body>
<h1>Web page</h1>
<p>Paragraph text</p>
<button type="button">Web button</button>
<input type="text" aria-label="Web field">
<a href="#end">Web link</a>
</body>
</html>"""
    }
}
