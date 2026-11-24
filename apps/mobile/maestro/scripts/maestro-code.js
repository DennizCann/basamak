// Maestro runScript (GraalJS, runs on the host machine): reads the latest sign-in code for EMAIL from the local Mailpit API.
// The code is only passed to the next step; it is never printed.
var list = json(http.get("http://localhost:8025/api/v1/search?query=to:" + EMAIL + "&limit=1").body);
var id = list.messages[0].ID;
var msg = json(http.get("http://localhost:8025/api/v1/message/" + id).body);
var m = msg.Text.match(/\b(\d{6})\b/);
output.code = m ? m[1] : "";
