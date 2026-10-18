extends Node2D
const HOST := "127.0.0.1"
const PORT := 8080
const INTERPOLATION_SPEED := 12.0
@export var player_id: int = 1
var peer: StreamPeerTCP
var rx_buffer := ""
var sprites: Dictionary = {}
var targets: Dictionary = {}
var move_clock := 0.0
func _ready() -> void:
    peer = StreamPeerTCP.new()
    var err := peer.connect_to_host(HOST, PORT)
    if err != OK:
        push_error("Backend connection failed: %s" % error_string(err))
func _process(delta: float) -> void:
    peer.poll()
    if peer.get_status() != StreamPeerTCP.STATUS_CONNECTED:
        return
    _read_network()
    move_clock += delta
    if move_clock >= 0.10:
        move_clock = 0.0
        var direction := Vector2.ZERO
        if Input.is_key_pressed(KEY_W) or Input.is_key_pressed(KEY_UP): direction.y -= 1
        if Input.is_key_pressed(KEY_S) or Input.is_key_pressed(KEY_DOWN): direction.y += 1
        if Input.is_key_pressed(KEY_A) or Input.is_key_pressed(KEY_LEFT): direction.x -= 1
        if Input.is_key_pressed(KEY_D) or Input.is_key_pressed(KEY_RIGHT): direction.x += 1
        if direction != Vector2.ZERO:
            direction = direction.normalized() * 5.0
            _send_command({"type":"move", "id":player_id, "dx":direction.x, "dy":direction.y})
    for id in sprites.keys():
        if targets.has(id):
            sprites[id].position = sprites[id].position.lerp(
                targets[id], 1.0 - exp(-INTERPOLATION_SPEED * delta))
func _read_network() -> void:
    var available := peer.get_available_bytes()
    if available <= 0:
        return
    var packet := peer.get_data(available)
    if packet[0] != OK:
        push_warning("TCP read error: %s" % error_string(packet[0]))
        return
    rx_buffer += packet[1].get_string_from_utf8()
    while true:
        var newline := rx_buffer.find("\n")
        if newline < 0:
            break
        var line := rx_buffer.substr(0, newline).strip_edges()
        rx_buffer = rx_buffer.substr(newline + 1)
        var data = JSON.parse_string(line)
        if data is Dictionary:
            _apply_snapshot(data)
func _apply_snapshot(data: Dictionary) -> void:
    var seen: Dictionary = {}
    for entity in data.get("entities", []):
        if not entity is Dictionary:
            continue
        var id := int(entity.get("id", -1))
        if id < 0:
            continue
        seen[id] = true
        var pos := Vector2(float(entity.get("x", 0.0)), float(entity.get("y", 0.0)))
        if not sprites.has(id):
            sprites[id] = _make_sprite(int(entity.get("type", 1)))
        targets[id] = pos
    for id in sprites.keys():
        if not seen.has(id):
            sprites[id].queue_free()
            sprites.erase(id)
            targets.erase(id)
func _make_sprite(kind: int) -> Sprite2D:
    var color := Color("#55d9ff")
    var size := 16
    if kind == 1:
        color = Color("#ff506e")
    elif kind == 2:
        color = Color("#ffe66d")
        size = 8
    var image := Image.create(size, size, false, Image.FORMAT_RGBA8)
    image.fill(color)
    var sprite := Sprite2D.new()
    sprite.texture = ImageTexture.create_from_image(image)
    add_child(sprite)
    return sprite
func _unhandled_input(event: InputEvent) -> void:
    if event is InputEventMouseButton and event.button_index == MOUSE_BUTTON_LEFT and event.pressed:
        if sprites.has(player_id):
            var aim: Vector2 = to_local(get_global_mouse_position()) - sprites[player_id].position
            _send_command({"type":"shoot", "player_id":player_id, "angle":rad_to_deg(aim.angle())})
func _send_command(message: Dictionary) -> void:
    if peer.get_status() != StreamPeerTCP.STATUS_CONNECTED:
        return
    var err := peer.put_data((JSON.stringify(message) + "\n").to_utf8_buffer())
    if err != OK:
        push_warning("TCP write error: %s" % error_string(err))
func _exit_tree() -> void:
    if peer:
        peer.disconnect_from_host()
