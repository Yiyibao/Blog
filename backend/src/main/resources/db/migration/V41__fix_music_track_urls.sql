-- 音乐曲目地址修正：seed 占位外链（cdn.example.com，404）与 pixabay 外链（403）
-- 均不可播，统一指向本站自托管默认音频 /audio/calm-piano-*.wav。
-- 管理端可随时在「音乐库」页替换为真实音乐文件。
update music_tracks
   set audio_url = '/audio/calm-piano-1.wav', cover_url = ''
 where track_id = 'track-1';

update music_tracks
   set audio_url = '/audio/calm-piano-2.wav', cover_url = ''
 where track_id = 'track-2';

update music_tracks
   set audio_url = '/audio/calm-piano-3.wav', cover_url = ''
 where track_id = 'track-3';

update music_tracks
   set audio_url = '/audio/calm-piano-1.wav', cover_url = ''
 where track_id = 'track-4';

update music_tracks
   set audio_url = '/audio/calm-piano-2.wav', cover_url = ''
 where track_id = 'track-5';
