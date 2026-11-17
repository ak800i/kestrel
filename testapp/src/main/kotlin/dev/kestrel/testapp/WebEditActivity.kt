package dev.kestrel.testapp

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout

/**
 * The text fields of the editing tests on a web page in a WebView, as prototype P4 had them: a search input in a form, a
 * textarea and a contenteditable, and a password input with a "Show password" button that turns it into a plain text
 * input, as login pages do. A script reports each field's text and the selection of the input and the textarea in a line
 * under the field (only the length for the password), and the form's submit and the button in the status line. The
 * page loads nothing from the network.
 */
class WebEditActivity : Activity() {
    // The script only reports what happens in the fields of this fixed page.
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = fixturePage("Web edit fixture")
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        page.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        web.loadDataWithBaseURL(null, PAGE, "text/html", "utf-8", null)
    }

    private companion object {
        const val PAGE = """<!doctype html>
<html>
<head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
<body>
<p id="web_status">Ready</p>
<form id="web_form"><input type="search" id="web_search" placeholder="Web search" aria-label="Web search"></form>
<p id="web_search_state"></p>
<textarea id="web_notes" rows="3" aria-label="Web notes"></textarea>
<p id="web_notes_state"></p>
<div id="web_rich" contenteditable="true" aria-label="Web rich text" style="border: 1px solid gray; min-height: 3em"></div>
<p id="web_rich_state"></p>
<input type="password" id="web_password" aria-label="Web password">
<button type="button" id="web_show">Show password</button>
<p id="web_password_state"></p>
<script>
function show(id, name, field) {
  var text = (field.value !== undefined ? field.value : field.innerText).replace(/\n/g, "\\n");
  var selection = field.selectionStart !== undefined ? " " + field.selectionStart + "-" + field.selectionEnd : "";
  document.getElementById(id).textContent = name + ": \"" + text + "\"" + selection;
}
var fields = [["web_search", "Web search"], ["web_notes", "Web notes"], ["web_rich", "Web rich text"]];
fields.forEach(function (pair) {
  var field = document.getElementById(pair[0]);
  var report = function () { show(pair[0] + "_state", pair[1], field); };
  ["input", "select", "keyup", "focus"].forEach(function (type) { field.addEventListener(type, report); });
  report();
});
document.addEventListener("selectionchange", function () {
  fields.forEach(function (pair) { show(pair[0] + "_state", pair[1], document.getElementById(pair[0])); });
});
var password = document.getElementById("web_password");
var reportPassword = function () {
  document.getElementById("web_password_state").textContent = "Web password: " + password.value.length + " characters";
};
["input", "keyup", "focus"].forEach(function (type) { password.addEventListener(type, reportPassword); });
reportPassword();
document.getElementById("web_show").addEventListener("click", function () {
  password.type = password.type === "password" ? "text" : "password";
  document.getElementById("web_status").textContent = password.type === "text" ? "Web password shown" : "Web password hidden";
});
document.getElementById("web_form").addEventListener("submit", function (event) {
  event.preventDefault();
  document.getElementById("web_status").textContent = "Submitted: \"" + document.getElementById("web_search").value + "\"";
});
</script>
</body>
</html>"""
    }
}
