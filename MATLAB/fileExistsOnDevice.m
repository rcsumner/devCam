function exists = fileExistsOnDevice(filename)
% exists = fileExistsOnDevice(filename)
%
% Checks if a file exists in the devCam directory on the attached device.
% Returns a boolean indicator of the file's existence.
%
% - - Input - -
% filename : a string indicating the filename and path relative to the main 
%           devCam directory on the device to look in.
%
% - - Ouptut - -
% exists : a boolean indicator
%
%
% Rob Sumner - May 2015

consts = devCamConstants(); % load relevant paths

file = fullcommand(consts.remote_dir,filename);

% Unfortunately, adb does not actually give feedback on the result of
% commands run in its shell, so we have to do the following:
% - in the adb shell, make a conditional which tests the existence of file
% - echo the (in-adb) status of that check. '0' = run successfully = file exists
% - parse the result of the system call to adb, which should contain this echoed value

% Construct the command taking the adb shell form " [ -e file]; echo $?"
command = fullcommand('[ -e',file,']');

% Have the local system's shell run this command in the adb shell
exists = adbshell(command);

end % end function