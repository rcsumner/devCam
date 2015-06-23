function success = adbpull(remoteFile,localDir)
% success =  adbpull(remoteFile, localDir)
%
% Pulls a file from the attached Android device onto the local machine. If
% the requested local directory does not already exist, this creates it.
%
% - - Input - -
% remoteFile : string of full file name and filepath relative to main 
%               devCam directory on the attached device
% localDir : string path of location to put file on local machine. To use
%            the current working directory, use '' or pwd.
%
% - - Output - -
% success : a boolean indicating if the command that was performed on the
%          device indicated successful execution or not
%
% Example: to pull the entire output directory from a capture named
% 'exposureBracket' to a new directory in  the current working directory,  
% use adbpull('Captured/exposureBracket',fullfile(pwd,'exposureBracket'));
%
% Rob Sumner - May 2015

consts = devCamConstants(); % get relevant paths

% Make sure the local target directory exists
if ~mkdir(localDir)
   error('Could not create local target directory.'); 
end

% Have the local system's shell run this command in the adb shell
command = fullcommand(consts.platform_tools_dir,'adb pull',...
    consts.remote_dir,remoteFile,localDir);
[status, result] = system(command);

% Return the boolean indicating pull success
success = (status==0);

end % end function 