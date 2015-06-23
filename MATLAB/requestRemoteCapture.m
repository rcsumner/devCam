function requestRemoteCapture(captureDesign,designName, format, width, height, processing,targetDir)
% requestRemoteCapture(captureDesign,designName, format, width, height, processing,targetDir)
%
% Request the attached device capture the input Capture Design (exposure
% sequence) in the desired format and size. The result will be saved on the
% device in the standard location.
%
% Note that prior to running, this deletes any data located at the target
% local directory or the design name's capture output directory on the
% device, so that any data seen at the end of the process is sure to have
% come from this request.
%
% Note: Currently, the user is required to make sure the format/size
% combination is valid. Otherwise devCam will fail when trying to create
% the remote session.
%
%
% - - Input - -
% captureDesign : struct array OR a string. If a struct array,  assumes
%                   it follows the standard conventions for an array of
%                   Exposures. If a string, assumes it is the filename
%                   (including path) of a .json containing a pre-written
%                   sequence of Exposures.
% designName : string name to label this capture design as.
% format : integer value indicating the desired output image format.
%           JPEG = 256, RAW_SENSOR = 32, YUV_420_888 = 35
% width, height :  integer values indicating target capture dimensions. It
%                 is the user's responsibility to make sure these are valid
%                 for the device and format requested.
% processing : integer value indicating the image processing requested from
%                 the camera pipeline. 0 = NONE, 1 = FAST, 2 = HIGH_QUALITY
% targetDir : string path to local dir you want the output data put into. A
%             new subdirectory of name designName will be created with the 
%             output data of the capture process.
%
%
% Rob Sumner - May 2015

consts = devCamConstants(); % load relevant paths

% Put the Capture Design .json on the device. If it doesn't exist yet,
% create it in the current working directory.
localJson = fullfile(targetDir,[designName '.json']);
if isstruct(captureDesign)
    mkdir(fullfile(targetDir,designName));
    savejson('',captureDesign,localJson);
end

% - - Delete any currently existing output folder of the same name, both 
% on the device, and locally - - 
adbshell(fullcommand('rm -r',consts.remote_dir,'Captured/',designName));
system(fullcommand('rm -r',fullfile(targetDir,designName)));

% Push the capture design json to the device
adbpush([designName '.json'],'Designs');

% Start the RemoteCaptureActivity
%startRemotePreview();
adbshell(fullcommand(consts.am_pre,consts.START_INTENT));


% - - Now tell devCam to capture that design - -
command = consts.am_pre; % pre-amble
command = fullcommand(command,consts.CAPTURE_INTENT); % tell it to capture
command = addIntentExtra(command,'DESIGN_NAME',designName); % this and the following are required
command = addIntentExtra(command,'FORMAT',format);
command = addIntentExtra(command,'HEIGHT',height);
command = addIntentExtra(command,'WIDTH',width);
command = addIntentExtra(command,'PROCESSING_SETTING',processing);
success = adbshell(command);


% - - 
if success
    disp('Capture Command successfully sent, waiting for output')
    waits = 30; % prevent againt an infinite loop, giving a reasonable amount of time
    
    % The devCam remote capture activity creates a flag dummy file while it
    % is processing the capture request, and removes it when it is done. 
    flag_file = 'captureflag';
    pause(1) % give the file some time to be created. lazy.
    while(fileExistsOnDevice(flag_file) && waits>0)
        pause(1);
        waits = waits-1;
        disp('"Capturing Flag" present on remote device.');
    end
    
    if waits>0
        disp('Outputs detected on device. Fetching now.')
    else
        disp('Timeout waiting for outputs to appear.')
    end
    
    % Note adbpull pulls files relative to the devCam folder on the device.
    adbpull(fullcommand('Captured/',designName),...
        fullfile(targetDir,designName));  % FIX THIS HARDWIRED LOCATION
    
end



% If we created the captureDesign.json for this capture, clean it up now.
if isstruct(captureDesign)
    disp('Removing locally generated temp json file.')
    system(fullcommand('rm',localJson));
end

end % end function