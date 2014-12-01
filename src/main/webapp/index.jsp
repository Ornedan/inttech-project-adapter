<html>
<head>
<title>Trackering</title>
</head>
<body>
	<script type="text/javascript">
		var wsUri = window.location.toString().replace("http","ws") + "tracker";

		function init() {
			output = document.getElementById("output");
		}
		function send_message() {
			websocket = new WebSocket(wsUri);
			websocket.onopen = function(evt) {
				onOpen(evt)
			};
			websocket.onmessage = function(evt) {
				onMessage(evt)
			};
			websocket.onerror = function(evt) {
				onError(evt)
			};
		}
		function onOpen(evt) {
			writeToScreen("Connected endpoint");
			doSend(textID.value);
		}
		function onMessage(evt) {
			writeToScreen("Message received: " + evt.data);
		}
		function onError(evt) {
			writeToScreen('ERROR: ' + evt.data);
		}
		function doSend(message) {
			writeToScreen("Message sent: " + message);
			websocket.send(message);
		}
		function writeToScreen(message) {
			var pre = document.createElement("p");
			pre.style.wordWrap = "break-word";
			pre.innerHTML = message;

			output.appendChild(pre);
		}
		window.addEventListener("load", init, false);
	</script>
	<div style="text-align: center;">
		<input onclick="send_message()" value="Send" type="button">
		<input id="textID" name="message" value="" type="text"><br>
	</div>
	<div id="output"></div>
</body>
</html>