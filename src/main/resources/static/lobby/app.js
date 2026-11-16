const wsProtocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';

const stompClient = new StompJs.Client({
    brokerURL: wsProtocol + window.location.host + '/AniQuiz'
});

stompClient.onConnect = (frame) => {
    setConnected(true);
    console.log('Connected: ' + frame);
    stompClient.subscribe('/game/greetings', (greeting) => {
        let name = '';
        try {
            const data = JSON.parse(greeting.body);
            name = data && data.name != null ? String(data.name) : '';
        } catch (e) {
            console.warn('Некорректное сообщение лобби', e);
        }
        showGreeting(name);
    });
};

stompClient.onWebSocketError = (error) => {
    console.error('Error with websocket', error);
};

stompClient.onStompError = (frame) => {
    console.error('Broker reported error: ' + frame.headers['message']);
    console.error('Additional details: ' + frame.body);
};

function setConnected(connected) {
    $("#connect").prop("disabled", connected);
    $("#disconnect").prop("disabled", !connected);
    if (connected) {
        $("#conversation").show();
    }
    else {
        $("#conversation").hide();
    }
    $("#greetings").empty();
}

function connect() {
    stompClient.activate();
}

function disconnect() {
    stompClient.deactivate();
    setConnected(false);
    console.log("Disconnected");
}

function sendName() {
    const name = String($("#name").val() || '').trim().slice(0, 32);
    if (!name) return;
    stompClient.publish({
        destination: "/gameResponse/hello",
        body: JSON.stringify({'name': name})
    });
}

function showGreeting(message) {
    const text = message == null ? '' : String(message);
    $("#greetings").append($('<tr>').append($('<td>').text(text)));
}

$(function () {
    $("form").on('submit', (e) => e.preventDefault());
    $( "#connect" ).click(() => connect());
    $( "#disconnect" ).click(() => disconnect());
    $( "#send" ).click(() => sendName());
});