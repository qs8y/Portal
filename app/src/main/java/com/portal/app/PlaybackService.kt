package com.portal.app

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Shows play/pause and skip (10 s back/forward) in the notification and on the lock screen. */
class SkipPlayer(p: Player) : ForwardingPlayer(p) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_PREVIOUS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS ||
            super.isCommandAvailable(command)

    override fun seekToNext() { wrappedPlayer.seekForward() }
    override fun seekToPrevious() { wrappedPlayer.seekBack() }
    override fun seekToNextMediaItem() { wrappedPlayer.seekForward() }
    override fun seekToPreviousMediaItem() { wrappedPlayer.seekBack() }
}

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val p = PlayerHolder.player
        if (p == null) { stopSelf(); return }
        val s = MediaSession.Builder(this, SkipPlayer(p)).build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}
