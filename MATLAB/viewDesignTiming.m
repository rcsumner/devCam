function viewDesignTiming(filename)
% viewDesignTiming()
% viewDesignTiming(filename)
%
% Displays the relative timing of the exposures captured during a
% capture sequence in devCam. The bars representing exposure time
% alternate in color only for visual distinction of adjacent exposures. 
% 
% Input is a JSON file with the exposure metadata about a capture sequence
% as generated by devCam, usually ending in "_capture_metadata.json".
% If no filename is supplied, prompts the user to select a JSON file. 
%
% Actual exposure times are in DARK alternating colors. Minimum frame times
% associated with each exposure are in matching LIGHT colors.
%
% Note that this uses timestamps associated with each frame, which may not
% actually be accurate as actual timekeeping. Check the 
% 'android.sensor.info.timestampSource' value in your device's generated
% cameraReport.json file. If it is not 'REALTIME' then this function's
% output may not be very reliable. 
% Note that my Nexus 5 reports 'UNKNOWN' for this value, but still seems to
% keep accurate enough relative time, so this warning may be somewhat
% unfounded.
% 
% Rob Sumner, March 2015

if nargin==0
    [fn, fp] = uigetfile('*.json');
    filename = [fp fn];
end
metadata = loadjson(filename);
figure

% Note, all times in the metadata are in ns. We care about disaplying ms,
% hence the "/1e6"s.

% Get the starting time so that we only display the relative
start = str2double(metadata{1}.android_sensor_timestamp);

for i = length(metadata):-1:1
    % Get the start time of this exposure, *relative to the beginning of
    % the first image's exposure*
    frameStart = (str2double(metadata{i}.android_sensor_timestamp)-start)/1e6;
    % Exposure length is the actual shutter-open time of the image
    exposureLength = str2double(metadata{i}.android_sensor_exposureTime)/1e6;
    % Frame length is the amount of time after the shutter opens until the
    % next image can open the shutter
    frameLength = str2double(metadata{i}.android_sensor_frameDuration)/1e6;
    
    Xexp = [frameStart,...
        frameStart+exposureLength,...
        frameStart+exposureLength,...
        frameStart];
    Xframe = [frameStart,...
        frameStart + frameLength,...
        frameStart + frameLength,...
        frameStart];
    Y = [1, 1, 0, 0];
    
    if mod(i,2)
        color = 'r';
    else
        color = 'b';
    end
    p = patch(Xframe,Y,color);
    set(p,'FaceAlpha',0.25);
    p = patch(Xexp,Y,color);
    
end
set(gca,'YTickLabel','')
xlabel('Time (ms)')
axis tight

% If this loaded JSON follows the standard naming convention, display the 
% name of this CaptureDesign in the title. If not, you'll just have to 
% remember which Design the plot came from.
if length(fn)>22
    if strcmp(fn(end-21:end),'_capture_metadata.json')
        title(['Capture Sequence Timing : ' fn(1:end-22)])
    else
        title('Capture Sequence Timing')
    end
end
