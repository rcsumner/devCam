function success = adbshell(command)
% success = adbshell(command)
%
% Perform a command using the adb (Android Debug Bridge) to communicate
% with an attached Andoid device. The user inputs only the command to be 
% executed, the function locates the adb binary using the devCamConstants()
% function and appends the system call to adb.
% 
% - - Input - - 
% command : a string of the command to be executed by the adb.
%
%
% - - Output - -
% success : a boolean indicating if the command that was performed on the
%          device indicated successful execution or not
%
%
% Example : To make the subdirectory Pictures/devCam/newDir on your device,
% simply use adbshell('mkdir newDir').
%
% Rob Sumner - May 2015

consts = devCamConstants(); % get relevant paths

% This ugly construct returns the command success state from the adb shell
% as an output to the local system shell, so we can see it.
command = fullcommand(consts.platform_tools_dir,'adb shell ''',command,...
    '> /dev/null 2>&1; echo $?''');

% Have the local system's shell run this command in the adb shell
[status, result] = system(command);

% If running the adb shell was unsuccessful, cause an error. 
% If it was successful, see if the output was the expected '0', indicating
% a successful execution of the conditional in the adb shell.
if (status==0)
    success = (str2num(result)==0);
else 
    error('Error running adb.');
end


end % end function