function success =  adbpush(localFile,remoteDir)
% success = adbpush(localFile, remoteDir)
%
% Pushes a file onto the device at the specified location using adb. If the
% remote target directory does not already exist, this tries to create it.
%
% - - Input - -
% localFile : string of full filepath and name of local file
% remoteDir : string of subdirectory structure relative to main devCam dir
%             on device. If pushing to the main directory, just use ''.
%
% - - Output - -
% success : a boolean indicating if the command that was performed on the
%          device indicated successful execution or not
%
% Example: to place ~/exposureBracket.json to the design directory in the
%          device, use adbpush('~/exposureBracket.json','Designs/');
%
% Rob Sumner - May 2015

consts = devCamConstants(); % get relevant paths

% First, "make sure" the directory exists. This doesn't actually check
% success because it cannot distinguish between failure due to the folder
% already existing and failure due to some other cause. So it just assumes
% it works, and an error should be caught be the subsequent command.
mkdirCommand = fullcommand('mkdir',consts.remote_dir,remoteDir);
adbshell(mkdirCommand);

% Now actually push the file there.
command = fullcommand(consts.platform_tools_dir,'adb push',localFile,...
    consts.remote_dir,remoteDir);
[status, result] = system(command);

% Return the boolean indicating push success
success = (status==0);

end % end function 